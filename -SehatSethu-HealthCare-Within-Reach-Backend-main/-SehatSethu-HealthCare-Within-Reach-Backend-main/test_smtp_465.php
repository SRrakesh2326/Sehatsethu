<?php
require "vendor/autoload.php";
use PHPMailer\PHPMailer\PHPMailer;
$mail = new PHPMailer(true);
$mail->isSMTP();
$mail->Host = "smtp.gmail.com";
$mail->SMTPAuth = true;
$mail->Username = "sehatsethu@gmail.com";
$mail->Password = "hmzhjdfhynkjdpjv";
$mail->SMTPSecure = PHPMailer::ENCRYPTION_SMTPS;
$mail->Port = 465;
$mail->SMTPOptions = ["ssl" => ["verify_peer" => false, "verify_peer_name" => false, "allow_self_signed" => true]];
$mail->Timeout = 20;
$mail->setFrom("sehatsethu@gmail.com", "SehatSethu");
$mail->addAddress("sehatsethu@gmail.com", "Test");
$mail->Subject = "SMTP Test 465";
$mail->Body = "Test email";
try {
    $ok = $mail->send();
    echo $ok ? "PORT 465 SENT OK" : ("PORT 465 FAILED: ".$mail->ErrorInfo);
} catch (Exception $e) {
    echo "PORT 465 EXCEPTION: ".$mail->ErrorInfo;
}
