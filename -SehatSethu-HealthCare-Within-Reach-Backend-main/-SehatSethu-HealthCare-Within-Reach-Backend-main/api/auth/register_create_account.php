<?php
require_once __DIR__ . "/../config.php";
require_once __DIR__ . "/../helpers.php";

if ($_SERVER["REQUEST_METHOD"] !== "POST") json_response(405, ["ok"=>false,"error"=>"POST only"]);

set_time_limit(20);
ini_set('default_socket_timeout', '20');

$data = read_json();
require_fields($data, ["full_name","email","password","role"]);

$fullName = trim((string)$data["full_name"]);
$email = normalize_email((string)$data["email"]);
$password = (string)$data["password"];
$role  = strtoupper(trim((string)$data["role"]));

if ($nameErr = validate_user_name($fullName)) json_response(422, ["ok"=>false,"error"=>$nameErr]);
if ($emailErr = validate_user_email($email)) json_response(422, ["ok"=>false,"error"=>$emailErr]);
if (!is_valid_role($role)) json_response(422, ["ok"=>false,"error"=>"Invalid role."]);
if ($passErr = validate_user_password($password)) json_response(422, ["ok"=>false,"error"=>$passErr]);

// Get connection (will emit JSON 503 on failure if helpers available)
$pdo = db();
$pdo->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);

/**
 * Ensure required tables/columns exist for this endpoint.
 * This is self-healing: if a missing column/table is detected,
 * we create/alter it on the fly to keep registrations working.
 */
function ensure_register_schema(PDO $pdo): void {
  static $checked = false;
  if ($checked) return;
  $checked = true;

  // helper: silent exec (best-effort, never throw)
  $safeExec = function(string $sql) use ($pdo): void {
    try { $pdo->exec($sql); } catch (Throwable $e) { /* ignore */ }
  };

  // Helper: does table exist?
  $tableExists = function(string $table) use ($pdo): bool {
    try {
      $st = $pdo->prepare("SHOW TABLES LIKE ?");
      $st->execute([$table]);
      return (bool)$st->fetchColumn();
    } catch (Throwable $e) {
      return false;
    }
  };

  // Helper: does column exist?
  $colExists = function(string $table, string $col) use ($pdo): bool {
    try {
      $st = $pdo->prepare("SHOW COLUMNS FROM `$table` LIKE ?");
      $st->execute([$col]);
      return (bool)$st->fetchColumn();
    } catch (Throwable $e) {
      return false;
    }
  };

  // Helper: does index exist?
  $indexExists = function(string $table, string $index) use ($pdo): bool {
    $st = $pdo->prepare("SHOW INDEX FROM `$table` WHERE Key_name = ?");
    try {
      $st->execute([$index]);
      return (bool)$st->fetch(PDO::FETCH_ASSOC);
    } catch (Throwable $e) {
      return false;
    }
  };

  // --- users table ---
  if (!$tableExists("users")) {
    $safeExec("
      CREATE TABLE `users` (
        `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
        `full_name` varchar(120) NOT NULL,
        `email` varchar(190) NOT NULL,
        `phone` varchar(20) DEFAULT NULL,
        `password_hash` varchar(255) NOT NULL,
        `role` enum('PATIENT','DOCTOR','PHARMACIST','ADMIN') NOT NULL DEFAULT 'PATIENT',
        `is_verified` tinyint(1) NOT NULL DEFAULT 0,
        `is_active` tinyint(1) NOT NULL DEFAULT 1,
        `profile_completed` tinyint(1) NOT NULL DEFAULT 0,
        `profile_submitted_at` timestamp NULL DEFAULT NULL,
        `admin_verification_status` enum('PENDING','UNDER_REVIEW','VERIFIED','REJECTED') NOT NULL DEFAULT 'PENDING',
        `admin_verified_by` bigint(20) unsigned DEFAULT NULL,
        `admin_verified_at` timestamp NULL DEFAULT NULL,
        `admin_rejection_reason` varchar(255) DEFAULT NULL,
        `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
        `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
        `last_login_at` timestamp NULL DEFAULT NULL,
        PRIMARY KEY (`id`),
        UNIQUE KEY `uq_users_email` (`email`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    ");
  } else {
    // add critical columns if missing
    if (!$colExists("users", "profile_completed")) {
      $safeExec("ALTER TABLE `users` ADD COLUMN `profile_completed` TINYINT(1) NOT NULL DEFAULT 0 AFTER `is_active`");
    }
    if (!$colExists("users", "admin_verification_status")) {
      $safeExec("ALTER TABLE `users` ADD COLUMN `admin_verification_status` ENUM('PENDING','UNDER_REVIEW','VERIFIED','REJECTED') NOT NULL DEFAULT 'PENDING' AFTER `profile_submitted_at`");
    }
    if (!$colExists("users", "is_verified")) {
      $safeExec("ALTER TABLE `users` ADD COLUMN `is_verified` TINYINT(1) NOT NULL DEFAULT 0 AFTER `role`");
    }
    // unique email safeguard (skip if duplicates exist to avoid fatal)
    if (!$indexExists("users", "uq_users_email")) {
      try {
        $dupe = $pdo->query("SELECT email FROM users GROUP BY email HAVING COUNT(*)>1 LIMIT 1")->fetchColumn();
        if (!$dupe) {
          $safeExec("ALTER TABLE `users` ADD UNIQUE KEY `uq_users_email` (`email`)");
        }
      } catch (Throwable $e) { /* ignore */ }
    }
  }

  // --- professional_verifications table ---
  if (!$tableExists("professional_verifications")) {
    $safeExec("
      CREATE TABLE `professional_verifications` (
        `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
        `user_id` bigint(20) unsigned NOT NULL,
        `role` enum('DOCTOR','PHARMACIST') NOT NULL,
        `application_no` varchar(32) DEFAULT NULL,
        `status` enum('PENDING','UNDER_REVIEW','VERIFIED','REJECTED') NOT NULL DEFAULT 'PENDING',
        `submitted_at` timestamp NULL DEFAULT NULL,
        `reviewed_by` bigint(20) unsigned DEFAULT NULL,
        `reviewed_at` timestamp NULL DEFAULT NULL,
        `rejection_reason` varchar(255) DEFAULT NULL,
        `notes` text DEFAULT NULL,
        `mci_number` varchar(30) DEFAULT NULL,
        PRIMARY KEY (`id`),
        UNIQUE KEY `uq_prof_user` (`user_id`),
        UNIQUE KEY `uq_prof_application_no` (`application_no`),
        UNIQUE KEY `uq_prof_ver_mci` (`mci_number`),
        UNIQUE KEY `uq_prof_app_no` (`application_no`),
        KEY `idx_prof_status` (`status`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    ");
  } else {
    if (!$colExists("professional_verifications", "status")) {
      $safeExec("ALTER TABLE `professional_verifications` ADD COLUMN `status` ENUM('PENDING','UNDER_REVIEW','VERIFIED','REJECTED') NOT NULL DEFAULT 'PENDING' AFTER `application_no`");
    }
    if (!$colExists("professional_verifications", "submitted_at")) {
      $safeExec("ALTER TABLE `professional_verifications` ADD COLUMN `submitted_at` timestamp NULL DEFAULT NULL AFTER `status`");
    }
    if (!$colExists("professional_verifications", "rejection_reason")) {
      $safeExec("ALTER TABLE `professional_verifications` ADD COLUMN `rejection_reason` varchar(255) DEFAULT NULL AFTER `reviewed_at`");
    }
  }

  // --- signup_pending table ---
  if (!$tableExists("signup_pending")) {
    $safeExec("
      CREATE TABLE `signup_pending` (
        `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
        `email` varchar(190) NOT NULL,
        `role` enum('PATIENT','DOCTOR','PHARMACIST','ADMIN') NOT NULL,
        `full_name` varchar(120) NOT NULL,
        `otp_hash` char(64) NOT NULL,
        `expires_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
        `last_sent_at` timestamp NULL DEFAULT NULL,
        `send_count` int(11) NOT NULL DEFAULT 1,
        `attempts` int(11) NOT NULL DEFAULT 0,
        `verified_at` timestamp NULL DEFAULT NULL,
        PRIMARY KEY (`id`),
        KEY `idx_signup_email_role` (`email`,`role`)
      ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
    ");
  } else {
    if (!$colExists("signup_pending", "role")) {
      $safeExec("ALTER TABLE `signup_pending` ADD COLUMN `role` enum('PATIENT','DOCTOR','PHARMACIST','ADMIN') NOT NULL AFTER `email`");
    }
    if (!$colExists("signup_pending", "full_name")) {
      $safeExec("ALTER TABLE `signup_pending` ADD COLUMN `full_name` varchar(120) NOT NULL AFTER `role`");
    }
  }
}

// Best-effort schema heal; continue on failure (will fail later if really broken)
try {
  ensure_register_schema($pdo);
} catch (Throwable $schemaErr) {
  // log but don't block; aim for maximum compatibility across hosts
  error_log("[register_create_account] schema self-heal warning: " . $schemaErr->getMessage());
}

// user must NOT exist
$stmt = $pdo->prepare("SELECT id FROM users WHERE email=? LIMIT 1");
$stmt->execute([$email]);
if ($stmt->fetch()) {
  json_response(409, ["ok"=>false,"code"=>"EMAIL_EXISTS","error"=>"Email already registered. Please sign in."]);
}

// ✅ IMPORTANT RULE:
// - DOCTOR/PHARMACIST: admin_verification_status must start as PENDING
// - PATIENT/ADMIN: VERIFIED
$adminStatus = ($role === "DOCTOR" || $role === "PHARMACIST") ? "PENDING" : "VERIFIED";

// ✅ profile_completed MUST be 0 right after signup for ALL roles (Admin can still be routed directly in app)
$profileCompleted = 0;

try {
  $pdo->beginTransaction();

  $pdo->prepare("
    INSERT INTO users (full_name, email, password_hash, role, is_verified, admin_verification_status, profile_completed)
    VALUES (?, ?, ?, ?, 1, ?, ?)
  ")->execute([
      $fullName,
      $email,
      password_hash($password, PASSWORD_BCRYPT),
      $role,
      $adminStatus,
      $profileCompleted
  ]);

  $userId = (int)$pdo->lastInsertId();

  // ✅ Create professional_verifications row (but NOT submitted yet)
  if ($role === "DOCTOR" || $role === "PHARMACIST") {
    $pdo->prepare("
      INSERT INTO professional_verifications (user_id, role, status, submitted_at)
      VALUES (?, ?, 'PENDING', NULL)
      ON DUPLICATE KEY UPDATE role=VALUES(role), status='PENDING', submitted_at=NULL, rejection_reason=NULL
    ")->execute([$userId, $role]);
  }

  // cleanup any stray pending record for this email/role
  $pdo->prepare("DELETE FROM signup_pending WHERE email=? AND role=?")->execute([$email, $role]);

  $pdo->commit();
} catch (Throwable $e) {
  if ($pdo->inTransaction()) $pdo->rollBack();

  // log detailed DB error with a trace id for support
  $traceId = bin2hex(random_bytes(8));
  $errMsg = sprintf(
    "[register_create_account] trace=%s message=%s code=%s",
    $traceId,
    $e->getMessage(),
    method_exists($e, 'getCode') ? $e->getCode() : 'n/a'
  );
  error_log($errMsg);

  $payload = ["ok"=>false,"error"=>"Could not create account. Please try again.","trace_id"=>$traceId];
  if (__is_debug()) {
    $payload["debug"] = $e->getMessage();
  }
  json_response(500, $payload);
}

json_response(200, [
  "ok"=>true,
  "message"=>"Account created successfully.",
  "admin_verification_status"=>$adminStatus,
  "profile_completed"=>$profileCompleted
]);
