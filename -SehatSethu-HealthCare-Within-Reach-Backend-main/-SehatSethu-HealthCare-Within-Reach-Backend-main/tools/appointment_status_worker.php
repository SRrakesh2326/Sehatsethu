<?php


require_once __DIR__ . "/../api/config.php";

if (php_sapi_name() !== "cli") {
  http_response_code(403);
  echo "CLI only\n";
  exit;
}

$pdo = db();
$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

// IMPORTANT: session strict mode (prevents invalid ENUM silently becoming '')
$pdo->exec("
  SET SESSION sql_mode = CONCAT(@@sql_mode,
    IF(LOCATE('STRICT_TRANS_TABLES', @@sql_mode) > 0, '', ',STRICT_TRANS_TABLES')
  )
");

// Make time consistent with your app (India). MySQL uses offsets, not IANA names.
$pdo->exec("SET SESSION time_zone = '+05:30'");

$meta = $pdo->query("
  SELECT DATABASE() AS db,
         NOW() AS now_db,
         @@global.time_zone AS global_tz,
         @@session.time_zone AS session_tz,
         @@sql_mode AS sql_mode
")->fetch(PDO::FETCH_ASSOC);

echo "[appt-worker] db={$meta['db']} now_db={$meta['now_db']} global_tz={$meta['global_tz']} session_tz={$meta['session_tz']}\n";
echo "[appt-worker] sql_mode={$meta['sql_mode']}\n";

// -------------------- Tunables --------------------
$pollSec        = 2;    // ✅ poll every 2 seconds
$earlySec       = 120;  // allow IN_PROGRESS 2 minutes early
$noShowGraceMin = 10;   // end + 10 min => NO_SHOW if no prescription
$warnPrintMax   = 3;    // print at most 3 warnings per tick

echo "[appt-worker] started poll={$pollSec}s early={$earlySec}s grace={$noShowGraceMin}min (DB NOW())\n";

function enumValues(string $type): array {
  // enum('A','B','C')
  if (!preg_match("/^enum\((.*)\)$/i", $type, $m)) return [];
  $inside = $m[1];
  preg_match_all("/'((?:\\\\'|[^'])*)'/", $inside, $mm);
  return array_map(fn($x) => str_replace("\\'", "'", $x), $mm[1] ?? []);
}

// Validate enum once at startup (fast + strict)
$col = $pdo->query("SHOW COLUMNS FROM appointments LIKE 'status'")->fetch(PDO::FETCH_ASSOC);
$type = (string)($col["Type"] ?? "");
if ($type === "" || stripos($type, "enum(") !== 0) {
  echo "[appt-worker] FATAL: appointments.status is not ENUM. Type={$type}\n";
  exit(1);
}
$vals = array_map('strtoupper', enumValues($type));
$need = ["BOOKED","CONFIRMED","IN_PROGRESS","COMPLETED","NO_SHOW"];
foreach ($need as $v) {
  if (!in_array($v, $vals, true)) {
    echo "[appt-worker] FATAL: appointments.status ENUM missing {$v}. Run migration.\n";
    exit(1);
  }
}

while (true) {
  $tickStart = microtime(true);

  try {
    // -------------------------------------------
    // A) Repair invalid enum zero-values ('') / NULLs safely
    //    Instead of forcing BOOKED always, we recompute correct status
    //    based on NOW(), schedule window, and prescription existence.
    // -------------------------------------------
    $stFix = $pdo->prepare("
      UPDATE appointments a
      LEFT JOIN prescriptions p ON p.appointment_id = a.id
      SET a.status = CASE
        -- If past end+grace => finalize (COMPLETED if prescription exists else NO_SHOW)
        WHEN NOW() >= DATE_ADD(
              DATE_ADD(a.scheduled_at, INTERVAL COALESCE(a.duration_min,15) MINUTE),
              INTERVAL :grace MINUTE
            )
          THEN CASE WHEN p.id IS NOT NULL THEN 'COMPLETED' ELSE 'NO_SHOW' END

        -- If inside start window => IN_PROGRESS
        WHEN NOW() >= DATE_SUB(a.scheduled_at, INTERVAL :early SECOND)
         AND NOW() <  DATE_ADD(a.scheduled_at, INTERVAL COALESCE(a.duration_min,15) MINUTE)
          THEN 'IN_PROGRESS'

        -- Otherwise default to BOOKED
        ELSE 'BOOKED'
      END,
      a.updated_at = NOW()
      WHERE a.status='' OR a.status IS NULL
    ");
    $stFix->execute([
      ":early" => (int)$earlySec,
      ":grace" => (int)$noShowGraceMin
    ]);
    $cFix = $stFix->rowCount();

    // -------------------------------------------
    // B) BOOKED/CONFIRMED -> IN_PROGRESS in the allowed window
    // -------------------------------------------
    $st1 = $pdo->prepare("
      UPDATE appointments
      SET status='IN_PROGRESS', updated_at=NOW()
      WHERE status IN ('BOOKED','CONFIRMED')
        AND NOW() >= DATE_SUB(scheduled_at, INTERVAL :early SECOND)
        AND NOW() <  DATE_ADD(scheduled_at, INTERVAL COALESCE(duration_min,15) MINUTE)
    ");
    $st1->execute([":early" => (int)$earlySec]);
    $c1 = $st1->rowCount();

    // -------------------------------------------
    // C) Finalize after end+grace (only if not already final)
    // -------------------------------------------
    $st2 = $pdo->prepare("
      UPDATE appointments a
      LEFT JOIN prescriptions p ON p.appointment_id = a.id
      SET a.status = CASE WHEN p.id IS NOT NULL THEN 'COMPLETED' ELSE 'NO_SHOW' END,
          a.updated_at = NOW()
      WHERE a.status IN ('BOOKED','CONFIRMED','IN_PROGRESS')
        AND NOW() >= DATE_ADD(
              DATE_ADD(a.scheduled_at, INTERVAL COALESCE(a.duration_min,15) MINUTE),
              INTERVAL :grace MINUTE
            )
    ");
    $st2->execute([":grace" => (int)$noShowGraceMin]);
    $c2 = $st2->rowCount();

    // -------------------------------------------
    // D) Late prescription safety:
    //    if an appointment is NO_SHOW but prescription exists, upgrade to COMPLETED.
    //    (prevents "NO_SHOW then COMPLETED" glitches from being stuck wrong)
    // -------------------------------------------
    $stLate = $pdo->prepare("
      UPDATE appointments a
      INNER JOIN prescriptions p ON p.appointment_id = a.id
      SET a.status='COMPLETED', a.updated_at=NOW()
      WHERE a.status='NO_SHOW'
    ");
    $stLate->execute();
    $cLate = $stLate->rowCount();

    // -------------------------------------------
    // E) Revert IN_PROGRESS -> BOOKED only if schedule moved to future
    //    (and only if we are still before start-early window)
    // -------------------------------------------
    $st3 = $pdo->prepare("
      UPDATE appointments
      SET status='BOOKED', updated_at=NOW()
      WHERE status='IN_PROGRESS'
        AND NOW() < DATE_SUB(scheduled_at, INTERVAL :early SECOND)
    ");
    $st3->execute([":early" => (int)$earlySec]);
    $c3 = $st3->rowCount();

    // -------------------------------------------
    // Warnings (detect silent coercions from OTHER sessions)
    // If some other script writes invalid ENUM, MySQL can still store '' with warnings
    // in non-strict sessions. We print warnings to catch that.
    // -------------------------------------------
    $warn = $pdo->query("SHOW WARNINGS")->fetchAll(PDO::FETCH_ASSOC);
    $warnCount = count($warn);

    $now = $pdo->query("SELECT NOW() AS n")->fetch(PDO::FETCH_ASSOC)["n"];
    echo "[appt-worker] tick now={$now} fixed_blank={$cFix} in_progress={$c1} finalized={$c2} late_rx={$cLate} reverted={$c3} warnings={$warnCount}\n";

    if ($warnCount > 0) {
      $top = array_slice($warn, 0, $warnPrintMax);
      foreach ($top as $w) {
        $lvl = $w["Level"] ?? "Warning";
        $msg = $w["Message"] ?? "";
        echo "[appt-worker] {$lvl}: {$msg}\n";
      }
    }

  } catch (Throwable $e) {
    echo "[appt-worker] ERROR: " . $e->getMessage() . "\n";
    // short backoff so it doesn’t spam
    usleep(250000); // 250ms
  }

  // Precise pacing: sleep remaining time to keep ~2s intervals
  $elapsed = microtime(true) - $tickStart;
  $sleep = max(0.0, $pollSec - $elapsed);
  usleep((int)round($sleep * 1_000_000));
}
