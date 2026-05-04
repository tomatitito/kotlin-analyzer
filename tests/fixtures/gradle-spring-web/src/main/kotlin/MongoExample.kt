import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query

class MongoExample(
    private val mongoOperations: MongoOperations,
) {
    fun run() {
        mongoOperations.
    }

    fun runPrefix() {
        mongoOperations.fi
    }

    fun criteriaPrefix() {
        Query.query(
            Criteria
                .where("farbId")
                .i
        )
    }
}
