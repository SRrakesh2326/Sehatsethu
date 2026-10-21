<?php
// api/mailer.php

require_once __DIR__ . '/../vendor/autoload.php';

use PHPMailer\PHPMailer\PHPMailer;
use PHPMailer\PHPMailer\Exception;

function sendMail($toEmail, $toName, $subject, $htmlBody, $textBody = ''): bool {
    $GLOBALS['MAILER_LAST_ERROR'] = null;

    // ---- SMTP credentials ----
    $smtpHost   = 'smtp.gmail.com';
    $smtpUser   = 'sehatsethu@gmail.com';
    $smtpPass   = 'hmzhjdfhynkjdpjv'; // Gmail App Password (16-char)

    // Try ports in order: 587 (STARTTLS), then 465 (SSL)
    $configs = [
        ['port' => 587, 'secure' => PHPMailer::ENCRYPTION_STARTTLS],
        ['port' => 465, 'secure' => PHPMailer::ENCRYPTION_SMTPS],
    ];

    $lastError = '';
    foreach ($configs as $cfg) {
        $mail = new PHPMailer(true);
        try {
            @ini_set('default_socket_timeout', '30');
            @ini_set('max_execution_time', '60');
            @set_time_limit(60);

            $mail->isSMTP();
            $mail->Host       = $smtpHost;
            $mail->SMTPAuth   = true;
            $mail->Username   = $smtpUser;
            $mail->Password   = $smtpPass;
            $mail->SMTPSecure = $cfg['secure'];
            $mail->Port       = $cfg['port'];
            $mail->Timeout    = 25;
            $mail->SMTPKeepAlive = false;

            $mail->SMTPOptions = [
                'ssl' => [
                    'verify_peer'       => false,
                    'verify_peer_name'  => false,
                    'allow_self_signed' => true,
                ]
            ];

            // Log SMTP dialog to Apache error_log for diagnostics
            $mail->SMTPDebug  = 2;
            $mail->Debugoutput = function($str, $level) {
                error_log("SMTP[$level]: " . trim($str));
            };

            $mail->setFrom($smtpUser, 'SehatSethu');
            $mail->addAddress($toEmail, $toName ?: $toEmail);

            $mail->isHTML(true);
            $mail->CharSet = 'UTF-8';
            $mail->Subject  = $subject;
            $mail->Body     = $htmlBody;
            $mail->AltBody  = $textBody ?: strip_tags($htmlBody);

            $ok = $mail->send();
            if ($ok) return true;

            $lastError = $mail->ErrorInfo ?: 'Unknown mailer error';
        } catch (Exception $e) {
            $lastError = $mail->ErrorInfo ?: $e->getMessage();
            error_log("SMTP port {$cfg['port']} failed: $lastError");
        }
    }

    $GLOBALS['MAILER_LAST_ERROR'] = $lastError;
    return false;
}
