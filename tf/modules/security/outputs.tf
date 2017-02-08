output "id" {
  value = "${aws_security_group.app.id}"
}

output "iam_instance_profile" {
  value = "${aws_iam_instance_profile.application_host.name}"
}

output "role_id" {
  value = "${aws_iam_role.application_host.id}"
}

output "role_arn" {
  value = "${aws_iam_role.application_host.arn}"
}
