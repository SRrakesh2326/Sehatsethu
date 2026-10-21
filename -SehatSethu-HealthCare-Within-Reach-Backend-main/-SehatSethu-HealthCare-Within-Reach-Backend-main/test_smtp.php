<?php
require "vendor/autoload.php";
use PHPMailer\PHPMailer\PHPMailer;
$mail = new PHPMailer(true);
$mail->isSMTP();
$mail->Host = "smtp.gmail.com";
$mail->SMTPAuth = true;
$mail->Username = "sehatsethu@gmail.com";
$mail->Password = "hmzhjdfhynkjdpjv";
$mail->SMTPSecure = PHPMailer::ENCRYPTION_STARTTLS;
$mail->Port = 587;
$mail->SMTPOptions = ["ssl" => ["verify_peer" => false, "verify_peer_name" => false, "allow_self_signed" => true]];
$mail->Timeout = 30;
$mail->setFrom("sehatsethu@gmail.com", "SehatSethu");
$mail->addAddress("sehatsethu@gmail.com", "Test");
$mail->Subject = "SMTP Test";
$mail->Body = "Test email";
try {
    $ok = $mail->send();
    echo $ok ? "SENT OK" : ("FAILED: ".$mail->ErrorInfo);
} catch (Exception $e) {
    echo "EXCEPTION: ".$mail->ErrorInfo;
}
