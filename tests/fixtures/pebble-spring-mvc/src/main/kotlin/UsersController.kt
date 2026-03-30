package fixture

interface Model {
    fun addAttribute(name: String, value: Any?): Model
}

class ModelAndView(val viewName: String) {
    fun addObject(name: String, value: Any?): ModelAndView = this
}

data class User(val name: String)
data class Outfit(val path: String, val relativePath: String)

data class Farbartikel(val brand: String) {
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
}
