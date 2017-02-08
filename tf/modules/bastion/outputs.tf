output "role_arn" {
  value = "${aws_iam_role.bastion_host.arn}"
}
