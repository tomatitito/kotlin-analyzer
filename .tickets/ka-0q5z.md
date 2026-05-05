---
id: ka-0q5z
status: closed
deps: []
links: [sid-b21l]
created: 2026-05-05T11:39:03Z
type: bug
priority: 2
assignee: Jens Kouros
parent: ka-sizl
---
# Unify duplicated model-attribute callee-name lists

BridgeContext.findSpringModelProducerDeclaration (line 952) and PebbleSpringIndex.isSupportedModelAttributeCall (line 490) independently maintain the same four-name set (addAttribute, addObject, modelAttribute, optionalModelAttribute). When a fifth name is added, one site will be missed.

Extract isSupportedModelAttributeCall to a shared internal function and call it from both sites. BridgeContext already calls into the spring index for pebble usages, so having it also use the shared helper is natural.
