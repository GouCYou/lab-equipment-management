-- Graduation demo: the user requested one shared login password (123456).
UPDATE sys_user SET password_hash='$2y$10$RNYNYKHZjgRSQUUazYaYf.V5xxVVQtYKVv2HFNNr6kf9/H/I6JjrS', auth_version=auth_version+1;
