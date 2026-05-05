package fixture

interface Model {
    fun addAttribute(name: String, value: Any?): Model
}

class ModelAndView(val viewName: String) {
    fun addObject(name: String, value: Any?): ModelAndView = this
}

data class User(val name: String)
data class Outfit(val path: String, val relativePath: String)

data class Farbartikel(val brand: String, val markeninfo: Markeninfo = Markeninfo(false)) {
    fun marke(): String = brand
}

class UsersController {
    fun detail(model: Model): String {
        val user = User("Ada")
        model.addAttribute("user", user)
        return "users/detail"
    }

    fun summary(): ModelAndView {
        val user = User("Bob")
        val modelAndView = ModelAndView("users/summary")
        modelAndView.addObject("user", user)
        return modelAndView
    }

    fun dynamicView(model: Model, suffix: String): String {
        val user = User("Eve")
        model.addAttribute("user", user)
        return "users/$suffix"
    }

    fun outfits(model: Model): String {
        val outfits: List<Outfit> = listOf(
            Outfit("/hero.jpg", "hero.jpg"),
        )
        model.addAttribute("outfits", outfits)
        return "outfits/carousel"
    }

    fun includeForwarding(model: Model): String {
        val user = User("Cara")
        model.addAttribute("user", user)
        return "users/include-parent"
    }

    fun unsupportedIncludeForwarding(model: Model): String {
        val user = User("Dana")
        model.addAttribute("user", user)
        return "users/include-unsupported-parent"
    }

    fun rendering(): RenderedRendering {
        val farbartikel = Farbartikel("Lux", Markeninfo(true))
        val outfitDto = OutfitDto(
            outfits = listOf(Outfit("/hero.jpg", "hero.jpg")),
            farbartikel = farbartikel,
        )
        val assetsConfig = AssetsConfig("/styles.css")
        return Rendering
            .view("outfits/rendering-carousel")
            .modelAttribute("outfits", outfitDto.outfits)
            .modelAttribute("farbartikel", farbartikel)
            .modelAttribute("isPureLuxury", farbartikel.markeninfo.pureLuxury)
            .optionalModelAttribute("cssPath", assetsConfig.cssPath)
            .build()
    }

    fun dynamicRenderingView(templateName: String): RenderedRendering =
        Rendering.view(templateName).build()

    fun renderingStatusOnly(): RenderedRendering =
        Rendering.status(204).build()

    fun dynamicRenderingModelName(attributeName: String): RenderedRendering {
        val user = User("Elle")
        return Rendering.view("outfits/dynamic-rendering-model")
            .modelAttribute(attributeName, user)
            .build()
    }
}

data class Markeninfo(val pureLuxury: Boolean)

data class OutfitDto(val outfits: List<Outfit>, val farbartikel: Farbartikel)

data class AssetsConfig(val cssPath: String)

class RenderedRendering(val viewName: String?)

class Rendering private constructor() {
    class Builder(private val viewName: String?) {
        fun modelAttribute(name: String, value: Any?): Builder = this
        fun build(): RenderedRendering = RenderedRendering(viewName)
    }

    companion object {
        fun view(viewName: String): Builder = Builder(viewName)
        fun status(status: Int): Builder = Builder(null)
    }
}

fun Rendering.Builder.optionalModelAttribute(name: String, value: Any?): Rendering.Builder = this
