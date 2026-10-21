SET FOREIGN_KEY_CHECKS=0;
SET SQL_MODE = "NO_AUTO_VALUE_ON_ZERO";
START TRANSACTION;
SET time_zone = "+00:00";

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;

CREATE DATABASE IF NOT EXISTS `sehatsethu` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `sehatsethu`;

DELIMITER $$
DROP PROCEDURE IF EXISTS `sp_tick_appointments`$$
CREATE DEFINER=`root`@`localhost` PROCEDURE `sp_tick_appointments` ()   BEGIN
  DECLARE nowts DATETIME;
  DECLARE GRACE_MIN INT DEFAULT 15;

  -- keep everything consistent for India time (works if server TZ is correct;
  -- recommended: set MySQL global time_zone to '+05:30')
  SET nowts = NOW();

  -- 1) UPCOMING: future slots (only for active booking-like statuses)
  UPDATE appointments
  SET status = 'UPCOMING'
  WHERE UPPER(COALESCE(status,'')) IN ('BOOKED','CONFIRMED','UPCOMING','IN_PROGRESS')
    AND scheduled_at > nowts;

  -- 2) IN_PROGRESS: scheduled_at <= now < scheduled_at + duration + grace
  UPDATE appointments
  SET status = 'IN_PROGRESS'
  WHERE UPPER(COALESCE(status,'')) IN ('BOOKED','CONFIRMED','UPCOMING','IN_PROGRESS')
    AND scheduled_at <= nowts
    AND nowts < DATE_ADD(
      scheduled_at,
      INTERVAL (COALESCE(NULLIF(duration_min,0),30) + GRACE_MIN) MINUTE
    );

  -- 3) ENDED: now >= end+grace => FINISHED if prescription exists else NO_SHOW
  UPDATE appointments a
  LEFT JOIN prescriptions p ON p.appointment_id = a.id
  SET a.status = CASE
      WHEN p.id IS NULL THEN 'NO_SHOW'
      ELSE 'FINISHED'
    END
  WHERE UPPER(COALESCE(a.status,'')) IN ('BOOKED','CONFIRMED','UPCOMING','IN_PROGRESS')
    AND nowts >= DATE_ADD(
      a.scheduled_at,
      INTERVAL (COALESCE(NULLIF(a.duration_min,0),30) + GRACE_MIN) MINUTE
    );

  -- 4) If prescription is created later for a NO_SHOW, flip to FINISHED
  UPDATE appointments a
  JOIN prescriptions p ON p.appointment_id = a.id
  SET a.status = 'FINISHED'
  WHERE UPPER(COALESCE(a.status,'')) = 'NO_SHOW';
END$$

DELIMITER ;

DROP TABLE IF EXISTS `appointments`;
CREATE TABLE IF NOT EXISTS `appointments` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `public_code` varchar(32) NOT NULL,
  `patient_id` bigint(20) UNSIGNED NOT NULL,
  `doctor_id` bigint(20) UNSIGNED NOT NULL,
  `specialty` varchar(120) NOT NULL,
  `consult_type` enum('AUDIO','VIDEO') NOT NULL,
  `symptoms` varchar(255) DEFAULT NULL,
  `fee_amount` int(11) NOT NULL DEFAULT 0,
  `scheduled_at` datetime NOT NULL,
  `duration_min` int(11) NOT NULL DEFAULT 15,
  `status` enum('BOOKED','CONFIRMED','IN_PROGRESS','COMPLETED','CANCELLED','REJECTED','NO_SHOW') NOT NULL DEFAULT 'BOOKED',
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `updated_at` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `public_code` (`public_code`),
  UNIQUE KEY `uq_doctor_slot` (`doctor_id`,`scheduled_at`),
  KEY `idx_appt_patient_time` (`patient_id`,`scheduled_at`),
  KEY `idx_appt_doctor_time` (`doctor_id`,`scheduled_at`),
  KEY `idx_appt_patient_doctor_time` (`patient_id`,`doctor_id`,`scheduled_at`),
  KEY `idx_appt_status_time` (`status`,`scheduled_at`),
  KEY `idx_appt_gate` (`patient_id`,`doctor_id`,`status`,`scheduled_at`),
  KEY `idx_appt_sched` (`scheduled_at`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP TABLE IF EXISTS `appointment_reminders`;
CREATE TABLE IF NOT EXISTS `appointment_reminders` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `appointment_id` bigint(20) UNSIGNED NOT NULL,
  `reminder_type` enum('24H','5H','10M') NOT NULL,
  `sent_at` datetime NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_appt_rem` (`appointment_id`,`reminder_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP TABLE IF EXISTS `appointment_reminder_state`;
CREATE TABLE IF NOT EXISTS `appointment_reminder_state` (
  `appointment_id` bigint(20) UNSIGNED NOT NULL,
  `last_stage` varchar(16) NOT NULL DEFAULT '',
  `patient_notif_id` bigint(20) UNSIGNED DEFAULT NULL,
  `doctor_notif_id` bigint(20) UNSIGNED DEFAULT NULL,
  `updated_at` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`appointment_id`),
  KEY `idx_last_stage` (`last_stage`),
  KEY `idx_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `doctor_availability`;
CREATE TABLE IF NOT EXISTS `doctor_availability` (
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `day_of_week` tinyint(4) NOT NULL,
  `enabled` tinyint(4) NOT NULL DEFAULT 0,
  `start_time` char(5) NOT NULL DEFAULT '09:00',
  `end_time` char(5) NOT NULL DEFAULT '17:00',
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`,`day_of_week`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP TABLE IF EXISTS `doctor_documents`;
CREATE TABLE IF NOT EXISTS `doctor_documents` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `doc_type` enum('MEDICAL_LICENSE','AADHAAR','MBBS_CERT') NOT NULL,
  `file_url` varchar(255) NOT NULL,
  `file_name` varchar(190) DEFAULT NULL,
  `mime_type` varchar(80) DEFAULT NULL,
  `file_size` bigint(20) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_doc_user_type` (`user_id`,`doc_type`),
  KEY `idx_doc_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `doctor_profiles`;
CREATE TABLE IF NOT EXISTS `doctor_profiles` (
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `specialization` varchar(120) NOT NULL,
  `registration_no` varchar(80) NOT NULL,
  `practice_place` varchar(190) NOT NULL,
  `phone` varchar(30) DEFAULT NULL,
  `experience_years` int(11) NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `fee_amount` int(11) DEFAULT 200,
  `languages_csv` varchar(255) DEFAULT 'Not Set',
  `rating` decimal(2,1) DEFAULT 5.0,
  `reviews_count` int(11) DEFAULT 0,
  `consultations_count` int(11) DEFAULT 0,
  `about_text` text DEFAULT 'Not Set',
  `education_text` varchar(255) DEFAULT 'Not Set',
  `works_at_text` varchar(255) DEFAULT 'Not Set',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uq_doctor_regno` (`registration_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `email_verifications`;
CREATE TABLE IF NOT EXISTS `email_verifications` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `token_hash` char(64) NOT NULL,
  `expires_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `used_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `send_count` int(11) NOT NULL DEFAULT 1,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `last_sent_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ev_user` (`user_id`),
  KEY `idx_ev_token_hash` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `medicine_catalog`;
CREATE TABLE IF NOT EXISTS `medicine_catalog` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `medicine_name` varchar(160) NOT NULL,
  `default_strength` varchar(60) NOT NULL DEFAULT '',
  `default_price` decimal(10,2) NOT NULL DEFAULT 0.00,
  `is_active` tinyint(4) NOT NULL DEFAULT 1,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_med_catalog` (`medicine_name`,`default_strength`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `medicine_requests`;
CREATE TABLE IF NOT EXISTS `medicine_requests` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `patient_user_id` bigint(20) UNSIGNED NOT NULL,
  `pharmacist_user_id` bigint(20) UNSIGNED NOT NULL,
  `medicine_name` varchar(190) NOT NULL,
  `strength` varchar(60) NOT NULL DEFAULT '',
  `quantity` int(11) NOT NULL DEFAULT 1,
  `status` enum('PENDING','AVAILABLE','CLOSED') NOT NULL DEFAULT 'PENDING',
  `marked_available_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT current_timestamp(),
  `updated_at` datetime NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_mr_pharmacist_status_created` (`pharmacist_user_id`,`status`,`created_at`),
  KEY `idx_mr_patient_created` (`patient_user_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

DROP TABLE IF EXISTS `notifications`;
CREATE TABLE IF NOT EXISTS `notifications` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `title` varchar(190) NOT NULL,
  `body` text DEFAULT NULL,
  `is_read` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `read_at` datetime DEFAULT NULL,
  `deleted_at` datetime DEFAULT NULL,
  `data_json` longtext DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notif_user_read` (`user_id`,`is_read`),
  KEY `idx_notifications_user_created` (`user_id`,`created_at`),
  KEY `idx_notifications_user_read` (`user_id`,`is_read`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `password_resets`;
CREATE TABLE IF NOT EXISTS `password_resets` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `token_hash` char(64) NOT NULL,
  `expires_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `verified_at` timestamp NULL DEFAULT NULL,
  `used_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `send_count` int(11) NOT NULL DEFAULT 1,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `last_sent_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pr_user` (`user_id`),
  KEY `idx_pr_token_hash` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `patient_profiles`;
CREATE TABLE IF NOT EXISTS `patient_profiles` (
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `gender` enum('MALE','FEMALE','OTHER') NOT NULL,
  `age` int(11) NOT NULL,
  `village_town` varchar(120) NOT NULL,
  `district` varchar(120) DEFAULT NULL,
  `medical_history` longtext DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `patient_vitals`;
CREATE TABLE IF NOT EXISTS `patient_vitals` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `patient_id` bigint(20) UNSIGNED NOT NULL,
  `recorded_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `client_recorded_at_ms` bigint(20) UNSIGNED DEFAULT NULL,
  `systolic` int(11) DEFAULT NULL,
  `diastolic` int(11) DEFAULT NULL,
  `sugar` int(11) DEFAULT NULL,
  `sugar_context` enum('FASTING','AFTER_MEAL','RANDOM') NOT NULL DEFAULT 'FASTING',
  `temperature_f` decimal(5,2) DEFAULT NULL,
  `weight_kg` decimal(6,2) DEFAULT NULL,
  `notes` varchar(600) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NULL DEFAULT NULL ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_vitals_patient_time` (`patient_id`,`recorded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `pharmacist_documents`;
CREATE TABLE IF NOT EXISTS `pharmacist_documents` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `doc_index` int(11) NOT NULL,
  `doc_type` varchar(40) DEFAULT NULL,
  `file_url` varchar(500) NOT NULL,
  `file_name` varchar(255) NOT NULL,
  `mime_type` varchar(80) NOT NULL,
  `file_size` int(11) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_pharm_docs_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `pharmacist_profiles`;
CREATE TABLE IF NOT EXISTS `pharmacist_profiles` (
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `pharmacy_name` varchar(190) NOT NULL,
  `drug_license_no` varchar(80) NOT NULL,
  `village_town` varchar(120) NOT NULL,
  `full_address` varchar(255) DEFAULT NULL,
  `availability_timings` varchar(255) NOT NULL DEFAULT '08:00 AM - 09:00 PM',
  `latitude` decimal(10,7) DEFAULT NULL,
  `longitude` decimal(10,7) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uq_pharm_license` (`drug_license_no`),
  KEY `idx_pharmacist_profiles_latlng` (`latitude`,`longitude`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `pharmacy_inventory`;
CREATE TABLE IF NOT EXISTS `pharmacy_inventory` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `pharmacist_user_id` bigint(20) UNSIGNED NOT NULL,
  `medicine_name` varchar(190) NOT NULL,
  `strength` varchar(80) DEFAULT NULL,
  `quantity` int(11) NOT NULL DEFAULT 0,
  `reorder_level` int(11) NOT NULL DEFAULT 5,
  `price_amount` decimal(10,2) NOT NULL DEFAULT 0.00,
  `price` decimal(10,2) DEFAULT NULL,
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_pharm_item` (`pharmacist_user_id`,`medicine_name`,`strength`),
  UNIQUE KEY `uq_pharm_med_strength` (`pharmacist_user_id`,`medicine_name`,`strength`),
  KEY `idx_pharm_inv_user` (`pharmacist_user_id`),
  KEY `idx_pharm_inv_qty` (`pharmacist_user_id`,`quantity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `prescriptions`;
CREATE TABLE IF NOT EXISTS `prescriptions` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `appointment_id` bigint(20) UNSIGNED DEFAULT NULL,
  `patient_id` bigint(20) UNSIGNED NOT NULL,
  `doctor_id` bigint(20) UNSIGNED NOT NULL,
  `title` varchar(255) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `diagnosis` text DEFAULT NULL,
  `doctor_notes` text DEFAULT NULL,
  `follow_up_text` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_presc_patient` (`patient_id`),
  KEY `idx_presc_doctor` (`doctor_id`),
  KEY `idx_presc_appt` (`appointment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `prescription_items`;
CREATE TABLE IF NOT EXISTS `prescription_items` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `prescription_id` bigint(20) UNSIGNED NOT NULL,
  `name` varchar(190) NOT NULL,
  `dosage` varchar(80) NOT NULL DEFAULT '',
  `frequency` varchar(80) NOT NULL DEFAULT '',
  `duration` varchar(80) NOT NULL DEFAULT '',
  `instructions` varchar(255) NOT NULL DEFAULT '',
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`id`),
  KEY `idx_pi_prescription` (`prescription_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `professional_verifications`;
CREATE TABLE IF NOT EXISTS `professional_verifications` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) UNSIGNED NOT NULL,
  `role` enum('DOCTOR','PHARMACIST') NOT NULL,
  `application_no` varchar(32) DEFAULT NULL,
  `status` enum('PENDING','UNDER_REVIEW','VERIFIED','REJECTED') NOT NULL DEFAULT 'PENDING',
  `submitted_at` timestamp NULL DEFAULT NULL,
  `reviewed_by` bigint(20) UNSIGNED DEFAULT NULL,
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

DROP TABLE IF EXISTS `signup_pending`;
CREATE TABLE IF NOT EXISTS `signup_pending` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
  `email` varchar(190) NOT NULL,
  `role` enum('PATIENT','DOCTOR','PHARMACIST','ADMIN') NOT NULL,
  `full_name` varchar(120) NOT NULL,
  `otp_hash` char(64) NOT NULL,
  `expires_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `last_sent_at` timestamp NULL DEFAULT NULL,
  `send_count` int(11) NOT NULL DEFAULT 1,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `verified_at` timestamp NULL DEFAULT NULL,
  `signup_token_hash` char(64) DEFAULT NULL,
  `signup_token_expires_at` timestamp NULL DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_signup_pending_email_role` (`email`,`role`),
  KEY `idx_signup_pending_email` (`email`),
  KEY `idx_signup_pending_last_sent` (`last_sent_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `users`;
CREATE TABLE IF NOT EXISTS `users` (
  `id` bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT,
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
  `admin_verified_by` bigint(20) UNSIGNED DEFAULT NULL,
  `admin_verified_at` timestamp NULL DEFAULT NULL,
  `admin_rejection_reason` varchar(255) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `last_login_at` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_users_email` (`email`),
  KEY `idx_users_role` (`role`),
  KEY `idx_users_active` (`is_active`),
  KEY `idx_users_admin_status` (`admin_verification_status`),
  KEY `fk_users_admin_verified_by` (`admin_verified_by`),
  KEY `idx_users_role_status_applied` (`role`,`admin_verification_status`,`profile_submitted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed default login users (password: 123456789)
INSERT INTO `users` (
  `full_name`,
  `email`,
  `phone`,
  `password_hash`,
  `role`,
  `is_verified`,
  `is_active`,
  `profile_completed`,
  `created_at`,
  `updated_at`
) VALUES
  ('Patient Account', 'gousebasha199189@gmail.com', NULL, '$2b$12$.e3Q2PX9T4VmleEL6MGrXe1xT/RyNwPbTMuMXnCpIqH7.q4uAq22K', 'PATIENT', 1, 1, 1, current_timestamp(), current_timestamp()),
  ('Platform Admin', 'sehatsethu@gmail.com', NULL, '$2b$12$.e3Q2PX9T4VmleEL6MGrXe1xT/RyNwPbTMuMXnCpIqH7.q4uAq22K', 'ADMIN', 1, 1, 1, current_timestamp(), current_timestamp()),
  ('Dr. Gouse Irfan', 'gouseirfan23@gmail.com', NULL, '$2b$12$.e3Q2PX9T4VmleEL6MGrXe1xT/RyNwPbTMuMXnCpIqH7.q4uAq22K', 'DOCTOR', 1, 1, 1, current_timestamp(), current_timestamp()),
  ('Pharmacist Ghouse Peer', 'ghousepeer199189@gmail.com', NULL, '$2b$12$.e3Q2PX9T4VmleEL6MGrXe1xT/RyNwPbTMuMXnCpIqH7.q4uAq22K', 'PHARMACIST', 1, 1, 1, current_timestamp(), current_timestamp())
ON DUPLICATE KEY UPDATE
  `password_hash` = VALUES(`password_hash`),
  `role` = VALUES(`role`),
  `is_verified` = VALUES(`is_verified`),
  `is_active` = VALUES(`is_active`),
  `profile_completed` = VALUES(`profile_completed`);


ALTER TABLE `appointments`
  ADD CONSTRAINT `fk_appt_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `fk_appt_patient` FOREIGN KEY (`patient_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `doctor_availability`
  ADD CONSTRAINT `fk_doc_av_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `doctor_documents`
  ADD CONSTRAINT `fk_doc_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `doctor_profiles`
  ADD CONSTRAINT `fk_doctor_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `email_verifications`
  ADD CONSTRAINT `fk_ev_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `notifications`
  ADD CONSTRAINT `fk_notif_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `password_resets`
  ADD CONSTRAINT `fk_pr_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `patient_profiles`
  ADD CONSTRAINT `fk_patient_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `patient_vitals`
  ADD CONSTRAINT `fk_vitals_patient` FOREIGN KEY (`patient_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `pharmacist_documents`
  ADD CONSTRAINT `fk_pharm_docs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `pharmacist_profiles`
  ADD CONSTRAINT `fk_pharmacist_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `pharmacy_inventory`
  ADD CONSTRAINT `fk_pharm_inv_user` FOREIGN KEY (`pharmacist_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `prescriptions`
  ADD CONSTRAINT `fk_presc_appointment` FOREIGN KEY (`appointment_id`) REFERENCES `appointments` (`id`) ON DELETE SET NULL,
  ADD CONSTRAINT `fk_presc_doctor` FOREIGN KEY (`doctor_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `fk_presc_patient` FOREIGN KEY (`patient_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `prescription_items`
  ADD CONSTRAINT `fk_pi_prescription` FOREIGN KEY (`prescription_id`) REFERENCES `prescriptions` (`id`) ON DELETE CASCADE;

ALTER TABLE `professional_verifications`
  ADD CONSTRAINT `fk_prof_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;

ALTER TABLE `users`
  ADD CONSTRAINT `fk_users_admin_verified_by` FOREIGN KEY (`admin_verified_by`) REFERENCES `users` (`id`) ON DELETE SET NULL;

DELIMITER $$
DROP EVENT IF EXISTS `evt_tick_appointments`$$
CREATE DEFINER=`root`@`localhost` EVENT `evt_tick_appointments` ON SCHEDULE EVERY 1 MINUTE STARTS '2025-12-31 02:56:09' ON COMPLETION NOT PRESERVE ENABLE DO CALL sp_tick_appointments()$$

DELIMITER ;
SET FOREIGN_KEY_CHECKS=1;
COMMIT;

/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
