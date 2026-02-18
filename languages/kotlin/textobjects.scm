; Functions

(function_declaration
  (function_body) @function.inside) @function.around

(secondary_constructor
  (statements) @function.inside) @function.around

(anonymous_initializer
  (statements) @function.inside) @function.around

(lambda_literal
  (_) @function.inside) @function.around

(anonymous_function
  (function_body) @function.inside) @function.around

(getter
  (function_body) @function.inside) @function.around

(setter
  (function_body) @function.inside) @function.around

; Classes

(class_declaration
  (class_body) @class.inside) @class.around

(object_declaration
  (class_body) @class.inside) @class.around

(companion_object
  (class_body) @class.inside) @class.around

(enum_entry
  (class_body) @class.inside) @class.around

; Comments

(line_comment) @comment.around

(multiline_comment) @comment.around
