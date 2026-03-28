const PREC = {
  CALL: 3,
  MEMBER: 2,
};

module.exports = grammar({
  name: "pebble",

  word: ($) => $.identifier,

  extras: () => [/\s+/],

  rules: {
    source_file: ($) =>
      repeat(
        choice(
          $.text,
          $.output,
          $.directive,
          $.comment,
        ),
      ),

    text: () => token(prec(-1, /[^{}]+|[{}]/)),

    output: ($) =>
      seq(
        "{{",
        repeat($.template_item),
        "}}",
      ),

    directive: ($) =>
      seq(
        "{%",
        field("name", alias($.identifier, $.tag_name)),
        repeat($.template_item),
        "%}",
      ),

    comment: () =>
      seq(
        "{#",
        repeat(choice(/[^#]+/, "#")),
        "#}",
      ),

    template_item: ($) =>
      choice(
        $.keyword,
        $.expression,
        $.operator,
        ".",
        ",",
        ":",
      ),

    expression: ($) =>
      choice(
        $.call_expression,
        $.member_expression,
        $.subscript_expression,
        $.parenthesized_expression,
        $.array_literal,
        $.string_literal,
        $.number_literal,
        $.boolean_literal,
        $.null_literal,
        $.identifier,
      ),

    parenthesized_expression: ($) =>
      seq(
        "(",
        repeat($.template_item),
        ")",
      ),

    array_literal: ($) =>
      seq(
        "[",
        repeat($.template_item),
        "]",
      ),

    call_expression: ($) =>
      prec.left(
        PREC.CALL,
        seq(
          field("function", choice($.identifier, $.member_expression)),
          "(",
          repeat($.template_item),
          ")",
        ),
      ),

    member_expression: ($) =>
      prec.left(
        PREC.MEMBER,
        seq(
          field(
            "object",
            choice(
              $.identifier,
              $.call_expression,
              $.member_expression,
              $.parenthesized_expression,
              $.subscript_expression,
            ),
          ),
          ".",
          field("property", $.identifier),
        ),
      ),

    subscript_expression: ($) =>
      prec.left(
        PREC.MEMBER,
        seq(
          field(
            "object",
            choice(
              $.identifier,
              $.call_expression,
              $.member_expression,
              $.parenthesized_expression,
              $.subscript_expression,
            ),
          ),
          "[",
          repeat($.template_item),
          "]",
        ),
      ),

    identifier: () => /[A-Za-z_][A-Za-z0-9_]*/,

    keyword: () =>
      token(prec(1, choice(
        "and",
        "as",
        "by",
        "else",
        "elseif",
        "endblock",
        "endfilter",
        "endif",
        "endfor",
        "endmacro",
        "endspaceless",
        "extends",
        "for",
        "if",
        "import",
        "in",
        "include",
        "is",
        "macro",
        "matches",
        "not",
        "or",
        "same",
        "with",
      ))),

    operator: () =>
      token(choice(
        "==",
        "!=",
        ">=",
        "<=",
        "??",
        "||",
        "&&",
        "|",
        "+",
        "-",
        "*",
        "/",
        "%",
        "=",
        ">",
        "<",
        "!",
        "~",
        "?",
      )),

    string_literal: () => token(choice(/"[^"]*"/, /'[^']*'/)),
    number_literal: () => token(/[0-9]+(\.[0-9]+)?/),
    boolean_literal: () => token(choice("true", "false")),
    null_literal: () => token("null"),
  },
});
