(comment) @comment
(tag_name) @keyword
(keyword) @keyword
((identifier) @keyword
  (#match? @keyword "^(and|as|by|else|elseif|endblock|endfilter|endif|endfor|endmacro|endspaceless|for|if|import|in|include|is|macro|matches|not|or|same|with)$"))
(identifier) @variable
(member_expression property: (identifier) @property)
(string_literal) @string
(number_literal) @number
(boolean_literal) @boolean
(null_literal) @constant.builtin
(operator) @operator

[
  "{{"
  "}}"
  "{%"
  "%}"
  "{#"
  "#}"
  "("
  ")"
  "["
  "]"
  "."
  ","
  ":"
] @punctuation.bracket
