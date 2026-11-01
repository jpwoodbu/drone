#!/usr/bin/env python3
"""Converts Markdown store descriptions to Google Play supported HTML.

Google Play descriptions support only a narrow subset of inline HTML tags
(`<b>`, `<i>`, `<u>`, and `<a href="...">`). All headings, lists, code blocks,
and other HTML tags are rejected or rendered literally by the Google Play
Store. This script converts supported Markdown syntax to Play Store HTML and
validates that no unsupported tags or Markdown features are present.
"""

import argparse
import html.parser
import re
import sys
from typing import Sequence

ALLOWED_HTML_TAGS = frozenset({'b', 'i', 'u', 'a'})
ALLOWED_TAG_ATTRIBUTES = {
    'a': frozenset({'href'}),
}

# Regex patterns for Markdown conversions.
LINK_PATTERN = re.compile(r'\[([^\]]+)\]\(([^)]+)\)')
BOLD_PATTERN = re.compile(r'\*\*(.+?)\*\*')
ITALIC_PATTERN = re.compile(
    r'(?<!\*)\*(?!\s)((?:[^\n*]|\*(?!\*))+?)(?<!\s)\*(?!\*)'
)
LIST_ITEM_PATTERN = re.compile(r'^\s*([-*+]|\d+\.)\s+(.*)')

# Regex patterns for unsupported Markdown structures.
UNSUPPORTED_PATTERNS = (
    (re.compile(r'^\s*#{1,6}\s+\S', re.MULTILINE), 'Markdown headers (`#`)'),
    (re.compile(r'^\s*>\s+\S', re.MULTILINE), 'Markdown blockquotes (`>`)'),
    (re.compile(r'```'), 'Markdown fenced code blocks (```````)'),
    (re.compile(r'`[^`\n]+`'), 'Markdown inline code (``` `code` ```)'),
    (re.compile(r'!\[.*?\]\(.*?\)'), 'Markdown images (`![...]`)'),
    (re.compile(r'^\s*\|.*?\|.*?\|\s*$', re.MULTILINE), 'Markdown tables (`|`)'),
)


class PlayStoreHtmlValidator(html.parser.HTMLParser):
  """Validates that parsed HTML contains only allowed Google Play tags."""

  def __init__(self) -> None:
    super().__init__()
    self.validation_errors: list[str] = []

  def handle_starttag(
      self, tag: str, attributes: list[tuple[str, str | None]]
  ) -> None:
    tag_name = tag.lower()
    if tag_name not in ALLOWED_HTML_TAGS:
      self.validation_errors.append(
          f"Unsupported HTML tag '<{tag}>' found. "
          f"Google Play only supports: {', '.join(sorted(ALLOWED_HTML_TAGS))}."
      )
      return

    allowed_attributes = ALLOWED_TAG_ATTRIBUTES.get(tag_name, frozenset())
    for attribute_name, _ in attributes:
      attribute_lower = attribute_name.lower()
      if attribute_lower not in allowed_attributes:
        self.validation_errors.append(
            f"Unsupported attribute '{attribute_name}' on tag '<{tag}>'."
        )


def validate_markdown(markdown_text: str) -> None:
  """Checks for unsupported Markdown structures before conversion.

  Args:
    markdown_text: Raw Markdown text to validate.

  Raises:
    ValueError: If unsupported Markdown syntax is detected.
  """
  for pattern, description in UNSUPPORTED_PATTERNS:
    if pattern.search(markdown_text):
      raise ValueError(
          f"Unsupported syntax detected: {description}. "
          "Google Play store descriptions do not support this element."
      )


def unwrap_markdown(markdown_text: str) -> str:
  """Unwraps soft-wrapped Markdown paragraphs and list items into single lines.

  Google Play descriptions treat every newline as an explicit hard break.
  This function collapses wrapped lines within paragraphs and list items into
  single, continuous lines while preserving double-newline block separations.

  Args:
    markdown_text: Raw Markdown text with soft-wrapped lines.

  Returns:
    Text with individual paragraphs and list items collapsed onto single lines.
  """
  blocks = re.split(r'\n\s*\n', markdown_text.strip())
  unwrapped_blocks: list[str] = []

  for block in blocks:
    lines = block.splitlines()
    if not lines:
      continue

    # Determine whether the block contains list items.
    if any(LIST_ITEM_PATTERN.match(line) for line in lines):
      list_items: list[str] = []
      current_item_lines: list[str] = []

      for line in lines:
        list_match = LIST_ITEM_PATTERN.match(line)
        if list_match:
          if current_item_lines:
            list_items.append(' '.join(current_item_lines))
          marker = list_match.group(1)
          # Normalize unordered bullet markers to standard asterisk bullets.
          normalized_marker = '*' if marker in ('-', '*', '+') else marker
          content = list_match.group(2).strip()
          current_item_lines = [f'{normalized_marker} {content}']
        else:
          stripped_line = line.strip()
          if stripped_line:
            current_item_lines.append(stripped_line)

      if current_item_lines:
        list_items.append(' '.join(current_item_lines))

      unwrapped_blocks.append('\n'.join(list_items))
    else:
      # Standard paragraph: join all lines into a single line.
      unwrapped_blocks.append(' '.join(line.strip() for line in lines))

  result = '\n\n'.join(unwrapped_blocks)
  if markdown_text.endswith('\n'):
    result += '\n'
  return result


def convert_markdown_to_play_store(markdown_text: str) -> str:
  """Converts Markdown text to Google Play supported HTML.

  Args:
    markdown_text: Raw Markdown input string.

  Returns:
    Converted text formatted for the Google Play Store.

  Raises:
    ValueError: If unsupported Markdown structures or HTML tags are detected.
  """
  validate_markdown(markdown_text)

  # Unwrap soft-wrapped paragraphs and list items into continuous lines.
  unwrapped_text = unwrap_markdown(markdown_text)

  # Convert links: [text](url) -> <a href="url">text</a>
  converted_text = LINK_PATTERN.sub(r'<a href="\2">\1</a>', unwrapped_text)

  # Convert bold: **text** -> <b>text</b>
  converted_text = BOLD_PATTERN.sub(r'<b>\1</b>', converted_text)

  # Convert italics: *text* -> <i>text</i> (excluding list bullets)
  converted_text = ITALIC_PATTERN.sub(r'<i>\1</i>', converted_text)

  # Validate generated markup.
  validator = PlayStoreHtmlValidator()
  validator.feed(converted_text)
  validator.close()

  if validator.validation_errors:
    error_summary = '\n'.join(validator.validation_errors)
    raise ValueError(f"HTML validation failed:\n{error_summary}")

  return converted_text


def main(arguments: Sequence[str] | None = None) -> int:
  """Main entry point for command-line execution."""
  argument_parser = argparse.ArgumentParser(
      description=(
          "Convert Markdown text to Google Play store description format "
          "and output to stdout."
      )
  )
  argument_parser.add_argument(
      "input_file",
      nargs="?",
      default="-",
      help="Path to the input Markdown file, or '-' to read from standard input.",
  )

  parsed_arguments = argument_parser.parse_args(arguments)

  try:
    if parsed_arguments.input_file == "-":
      input_text = sys.stdin.read()
    else:
      with open(parsed_arguments.input_file, "r", encoding="utf-8") as file:
        input_text = file.read()

    output_text = convert_markdown_to_play_store(input_text)
    sys.stdout.write(output_text)
    return 0
  except (ValueError, OSError) as error:
    sys.stderr.write(f"Error: {error}\n")
    return 1


if __name__ == "__main__":
  sys.exit(main())
