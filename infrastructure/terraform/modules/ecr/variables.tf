variable "name" {
  description = "Repository name."
  type        = string
}

variable "force_delete" {
  description = "Allow deleting a repository that still contains images (dev only)."
  type        = bool
  default     = false
}

variable "tags" {
  type    = map(string)
  default = {}
}
