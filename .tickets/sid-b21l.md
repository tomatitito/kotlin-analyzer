---
id: sid-b21l
status: closed
deps: []
links: [ka-0q5z]
created: 2026-05-05T12:28:17Z
type: bug
priority: 2
assignee: Jens Kouros
parent: ka-sizl
---
# Fix definition priority: model attribute names before view navigation

definitionForKotlinView ran before findSpringModelProducerDeclaration in BridgeNavigation.definition(), so clicking a model attribute name string (e.g. "farbartikel" in .modelAttribute("farbartikel", ...)) inside a Rendering chain matched a spurious view fact and navigated to the template file instead of the pebble variable usage. Fix: reorder the checks so findSpringModelProducerDeclaration runs first. Added test: definition from Rendering modelAttribute name navigates to pebble usage.
