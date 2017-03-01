resource "aws_alb" "main" {
  name            = "${var.environment}-${var.name}-alb"
  internal        = false
  security_groups = ["${var.security_groups}"]
  subnets         = "${var.subnet_ids}"
  enable_deletion_protection = false
}

resource "aws_alb_target_group" "website" {
  name     = "${var.environment}-${var.name}-alb-tg"
  port     = "${var.forward_port}"
  protocol = "HTTP"
  vpc_id   = "${var.vpc_id}"
}
