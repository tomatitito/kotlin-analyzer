---
id: ka-e7w6
status: open
deps: []
links: []
created: 2026-05-05T09:03:29Z
type: feature
priority: 2
assignee: Jens Kouros
parent: ka-gwtp
tags: [pebble, spring, sidecar, lsp, rendering]
---
# Support Spring WebFlux Rendering modelAttribute chains for Pebble navigation

Real-world Spring WebFlux controllers can return Pebble templates with the fluent Rendering builder API instead of Model.addAttribute or ModelAndView.addObject. Example from /Volumes/sourcecode/meta-breuninger/entdecken/reco/outfit-genie/src/main/kotlin/com/breuninger/reco/outfitgenie/ports/inbound/frontend/web/RenderOutfitController.kt:

return Rendering
    .view("outfits/carousel")
    .modelAttribute("outfits", outfitDto.outfits)
    .modelAttribute("farbartikel", outfitDto.farbartikel)
    .modelAttribute("isPureLuxury", farbartikel.markeninfo.pureLuxury)
    .optionalModelAttribute("cssPath", assetsConfig.cssPath)
    .build()

Current Pebble/Kotlin navigation only indexes simple model.addAttribute("name", value) and ModelAndView.addObject("name", value) patterns once a single static template view has been found. As a result, go-to-definition/references for variables in src/frontend/templates/outfits/carousel.peb cannot resolve back to Kotlin producers in RenderOutfitController.

## Design

Investigation findings:

- sidecar/src/main/kotlin/dev/kouros/sidecar/PebbleSpringIndex.kt indexes each KtNamedFunction by first extracting view facts, then scanning KtCallExpression nodes whose callee text is exactly addAttribute or addObject.
- extractViewFacts/viewFactFromExpression currently recognizes only plain string return values and ModelAndView(...) constructor calls. It does not recognize Rendering.view("...") or fluent builder chains ending in build().
- Rendering.Builder.modelAttribute("name", expr) and project extension optionalModelAttribute("name", expr) are static enough to index conservatively: literal name + visible expression producer.
- Need to associate modelAttribute calls with the template selected by the same Rendering.view("...") chain/function. A conservative first implementation can support a function with exactly one resolved Rendering.view(...) template, matching the existing single-template behavior.

Implementation notes:

1. Extend Spring view extraction to recognize static WebFlux Rendering view declarations:
   - Rendering.view("template/name")
   - org.springframework.web.reactive.result.view.Rendering.view("template/name") if callee text exposes qualification
   - optionally Rendering.status(...).build() is not a view and should be ignored.

2. Extend model attribute extraction to accept fluent builder methods:
   - modelAttribute("name", expr)
   - optionalModelAttribute("name", expr) for local project extension/wrapper where the value is still explicit
   - keep callee/name + literal first arg checks conservative; do not try to follow arbitrary wrapper methods yet.

3. Reuse existing producer resolution where possible, but consider expression producers beyond simple name references:
   - outfitDto.outfits should at least navigate to the expression/range or, if feasible later, property segment type/member
   - farbartikel.markeninfo.pureLuxury should not be mis-resolved; conservative fallback to expression location is acceptable if member resolution is not available
   - local vals such as trackingDataDto should still resolve to the local property.

4. Add fixture coverage for a Rendering-style controller and a Pebble template under a frontend template root such as src/frontend/templates/outfits/carousel.peb.

5. Ensure Kotlin references for producers include Pebble usages for variables supplied through Rendering.modelAttribute/optionalModelAttribute.

## Acceptance Criteria

- Go to definition from Pebble variables supplied by Rendering.view(...).modelAttribute(...) resolves to the indexed Kotlin producer or conservative expression range.
- Go to definition from Pebble variables supplied by optionalModelAttribute("name", expr) resolves when the name is a literal and expression is statically present.
- Kotlin find references for those producers includes Pebble usages in the associated template.
- Static view navigation from Rendering.view("outfits/carousel") resolves to the corresponding .peb/.pebble template.
- Dynamic template names, dynamic model names, and unsupported builder/helper patterns return no result rather than incorrect links.
- Existing model.addAttribute and ModelAndView.addObject tests continue to pass.
