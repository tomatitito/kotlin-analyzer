package service

import model.Person

class PersonService {
    private val people = mutableListOf<Person>()

    fun addPerson(person: Person) {
        people.add(person)
    }

    fun findPerson(id: Long): Person? {
        return people.find { it.id == id }
    }

    fun allPeople(): List<Person> {
        return people.toList()
    }
}
