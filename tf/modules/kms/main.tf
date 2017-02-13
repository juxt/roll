data "template_file" "kms_policy" {
  template = "${file("${path.module}/files/policy.json")}"
  vars {
    root_arn = "${var.root_arn}"
    admin_arns = "${jsonencode(var.admin_arns)}"
    user_arns = "${jsonencode(var.user_arns)}"
    attachment_arns = "${jsonencode(var.attachment_arns)}"
  }
}

resource "aws_kms_key" "default" {
  description = "Key"
  deletion_window_in_days = 20
  policy = "${data.template_file.kms_policy.rendered}"
}

resource "aws_kms_alias" "alias" {
  name = "alias/${var.alias}"
  target_key_id = "${aws_kms_key.default.key_id}"
}
