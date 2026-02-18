; Detect `fun main()` and `fun main(args: Array<String>)` as runnables

(source_file
  (package_header
    (identifier) @kotlin_package_name)?
  (function_declaration
    (simple_identifier) @_name
    (#eq? @_name "main")
  ) @run
  (#set! tag "kotlin-main")
)

; Detect @Test annotated functions inside a class

(source_file
  (package_header
    (identifier) @kotlin_package_name)?
  (class_declaration
    (type_identifier) @kotlin_class_name
    (class_body
      (function_declaration
        (modifiers
          (annotation
            (user_type
              (type_identifier) @_annotation
              (#eq? @_annotation "Test"))))
        (simple_identifier) @kotlin_method_name
      ) @run
      (#set! tag "kotlin-test-method")
    )
  )
)

; Detect @Test annotated top-level functions (less common but valid)

(source_file
  (package_header
    (identifier) @kotlin_package_name)?
  (function_declaration
    (modifiers
      (annotation
        (user_type
          (type_identifier) @_annotation
          (#eq? @_annotation "Test"))))
    (simple_identifier) @kotlin_method_name
  ) @run
  (#set! tag "kotlin-test-function")
)

; Detect entire test classes (classes that contain @Test methods)

(source_file
  (package_header
    (identifier) @kotlin_package_name)?
  (class_declaration
    (type_identifier) @kotlin_class_name
    (class_body
      (function_declaration
        (modifiers
          (annotation
            (user_type
              (type_identifier) @_annotation
              (#eq? @_annotation "Test"))))))
  ) @run
  (#set! tag "kotlin-test-class")
)
