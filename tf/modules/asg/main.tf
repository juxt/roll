resource "aws_launch_configuration" "app" {
  name_prefix          = "${var.environment}"
  image_id             = "${var.ami}"
  instance_type        = "${var.instance_type}"
  security_groups      = ["${var.security_group_id}"]
  iam_instance_profile = "${var.iam_instance_profile}"
  user_data            = "${var.user_data}"
  key_name             = "${var.key_name}"

  lifecycle {
    create_before_destroy = false
  }
}

resource "aws_autoscaling_group" "app" {
  availability_zones   = "${var.availability_zones}"
  name                 = "${var.environment}"
  max_size             = "${var.instance_count}"
  min_size             = "${var.instance_count}"
  launch_configuration = "${aws_launch_configuration.app.name}"
  target_group_arns    = ["${var.target_group_arns}"]

  tag {
    key                 = "Name"
    value               = "${var.environment}-app"
    propagate_at_launch = true
  }

  lifecycle {
    create_before_destroy = true
  }
}
