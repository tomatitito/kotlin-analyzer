class UserService {
    private val users = mutableListOf<User>()

    fun addUser(user: User) {
        users.add(user)
    }

    fun findUser(id: Long): User? {
        return users.find { it.id == id }
    }

    fun allUsers(): List<User> {
        return users.toList()
    }
}
