package fixture

interface Model {
    fun addAttribute(name: String, value: Any?): Model
}

class ModelAndView(val viewName: String) {
    fun addObject(name: String, value: Any?): ModelAndView = this
}

data class User(val name: String)

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
}
