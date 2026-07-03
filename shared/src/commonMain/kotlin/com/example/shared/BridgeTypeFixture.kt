package com.example.shared

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Comprehensive fixture covering every declaration type and type-reference permutation
 * that the ApiModel must represent.
 *
 * This file exists purely to exercise the klib reader and model validation pipeline.
 * Method bodies use [TODO] stubs — nothing here is meant to be called at runtime.
 */

// ── Enum ──────────────────────────────────────────────────────────────────────

enum class FixtureStatus { ACTIVE, INACTIVE, PENDING }

// ── Data classes ──────────────────────────────────────────────────────────────

/** Nested data class — appears as a [ClassRef] field inside [FixtureUser]. */
data class FixtureAddress(
    val street: String,
    val city: String,
    val zip: String?,           // nullable primitive
)

/**
 * Data class exercising every primitive kind, nullable fields, enum reference,
 * nested ClassRef, and collection fields.
 */
data class FixtureUser(
    val id: String,
    val age: Int,
    val score: Double,
    val active: Boolean,
    val byteFlag: Byte,
    val longId: Long,
    val initial: Char,
    val ratio: Float,
    val status: FixtureStatus,          // ClassRef → enum
    val address: FixtureAddress?,       // ClassRef, nullable
    val tags: List<String>,             // CollectionType LIST
    val metadata: Map<String, Int>,     // CollectionType MAP
    val aliases: Set<String>,           // CollectionType SET
)

/** Data class with collection-of-record fields — element-wise codec conversion required. */
data class FixtureTeam(
    val name: String,
    val members: List<FixtureUser>,          // List of data classes
    val leads: Map<String, FixtureUser>,     // Map with data-class values
    val states: List<FixtureStatus>,         // List of enums
    val charter: Set<String>,                // Set crosses as a JS array
)

// ── Sealed class — all three variant kinds ────────────────────────────────────

/**
 * Sealed class with:
 * - [Success]  — DataVariant (data class with fields)
 * - [Empty]    — ObjectVariant (singleton, no fields)
 * - [Failure]  — ClassVariant (regular class with fields)
 * - [Partial]  — ClassVariant with isAbstract = true
 */
sealed class FixtureResult {
    data class Success(val user: FixtureUser, val code: Int) : FixtureResult()
    object Empty : FixtureResult()
    class Failure(val message: String, val retryable: Boolean) : FixtureResult()
    abstract class Partial(val hint: String) : FixtureResult()
}

/** Second sealed class to test multi-sealed-class modules. */
sealed class FixtureAuthState {
    object LoggedOut : FixtureAuthState()
    data class LoggedIn(val user: FixtureUser, val token: String) : FixtureAuthState()
    object Refreshing : FixtureAuthState()
}

/** Sealed interface — must bridge as a tagged record, not a registry-backed interface. */
sealed interface FixturePayment {
    data class Card(val last4: String) : FixturePayment
    object Cash : FixturePayment
}

/** Sealed class whose variants are declared at file top level (legal since Kotlin 1.5). */
sealed class FixtureShape

data class FixtureCircle(val radius: Double) : FixtureShape()
data class FixtureSquare(val side: Double) : FixtureShape()

/** Class with user-declared members that shadow data-class synthesized names — must be bridged. */
class FixtureNamedOverrides {
    override fun toString(): String = "custom-tostring"
    fun copy(tag: String): String = "copy:$tag"
}

// ── Interface ─────────────────────────────────────────────────────────────────

/** Interface exercising all three function kinds. */
interface FixtureRepository {
    fun findById(id: String): FixtureUser                   // SYNC, ClassRef return
    suspend fun fetchById(id: String): FixtureUser           // SUSPEND, ClassRef return
    suspend fun countAll(): Int                              // SUSPEND, numeric return (resolve wire contract)
    suspend fun findByStatus(status: FixtureStatus): Int     // SUSPEND, enum param (call<Fn> event payload)
    fun observeAll(): Flow<List<FixtureUser>>                // FLOW, nested Collection+ClassRef
}

/** Zero-function marker interface — TS emits a type-only interface, no runtime wrapper. */
interface FixtureMarker

// ── Object ────────────────────────────────────────────────────────────────────

/** Singleton object exercising all three function kinds. */
object FixtureAnalytics {
    fun track(event: String): Unit = Unit
    suspend fun flush(): Boolean = true
    fun events(): Flow<String> = flow {
        val labels = listOf("click", "view", "purchase", "share", "like")
        var i = 0
        while (true) {
            emit("${labels[i % labels.size]}_$i")
            i++
            delay(1_000)
        }
    }
}

// ── Abstract class ────────────────────────────────────────────────────────────

/** Abstract class — isAbstract = true on KmpClass. */
abstract class FixtureBaseProcessor {
    abstract fun process(input: String): String
    abstract suspend fun processAsync(input: String): String
}

/**
 * Abstract class that CANNOT be JS-implemented: constructor parameter + final concrete function.
 * The generators must still bridge the KMP-implemented direction but skip create()/resolve.
 */
abstract class FixtureConfiguredProcessor(val label: String) {
    fun describe(): String = "processor:$label"
    abstract suspend fun run(input: String): String
}

// ── Concrete class — every primitive as param and return ──────────────────────

class FixturePrimitivesApi {
    // All 9 primitive kinds as parameters in one function
    fun allPrimitives(
        s: String,
        i: Int,
        l: Long,
        d: Double,
        f: Float,
        b: Boolean,
        by: Byte,
        sh: Short,
        c: Char,
    ): Unit = Unit

    // Each primitive as a distinct return type
    fun returnString(): String = ""
    fun returnInt(): Int = 0
    fun returnLong(): Long = 0L
    fun returnDouble(): Double = 0.0
    fun returnFloat(): Float = 0f
    fun returnBoolean(): Boolean = false
    fun returnByte(): Byte = 0
    fun returnShort(): Short = 0
    fun returnChar(): Char = 'a'

    // Nullable primitives
    fun nullableParam(s: String?, i: Int?): String? = s
    fun returnNullableInt(): Int? = null
    fun returnNullableBool(): Boolean? = null
}

// ── Concrete class — collections and type argument variance ───────────────────

class FixtureCollectionsApi {
    // Basic collections
    fun getStringList(): List<String> = emptyList()
    fun getIntSet(): Set<Int> = emptySet()
    fun getStringIntMap(): Map<String, Int> = emptyMap()

    // Nullable collection reference
    fun getNullableList(): List<String>? = null

    // Nested generic
    fun getNestedList(): List<List<String>> = emptyList()
    fun getUserMap(): Map<String, List<FixtureUser>> = emptyMap()

    // ClassRef inside collection
    fun getUserList(): List<FixtureUser> = emptyList()

    // Covariant type argument (out)
    fun covariantList(): List<out FixtureUser> = emptyList()

    // Star projection (*)
    fun starList(): List<*> = emptyList<Any>()
    fun starMap(): Map<*, *> = emptyMap<Any, Any>()

    // Non-String map key — must be skipped loudly (JS objects are string-keyed)
    fun badMap(): Map<Int, String> = emptyMap()
}

// ── Concrete class — all async patterns ───────────────────────────────────────

class FixtureAsyncApi {
    // SYNC
    fun greet(name: String): String = "Hello, $name"

    // SUSPEND
    suspend fun fetchUser(id: String): FixtureUser {
        delay(200)
        return FixtureUser(
            id          = id,
            age         = 28,
            score       = 4.85,
            active      = true,
            byteFlag    = 7,
            longId      = 1_234_567_890L,
            initial     = 'K',
            ratio       = 0.75f,
            status      = FixtureStatus.ACTIVE,
            address     = FixtureAddress(street = "1 KMP Lane", city = "Kotlinville", zip = "12345"),
            tags        = listOf("android", "kmp", "bridge"),
            metadata    = mapOf("version" to 1, "build" to 42),
            aliases     = setOf("alias_$id", "fixture"),
        )
    }

    suspend fun fetchNullableUser(id: String): FixtureUser? = null
    suspend fun deleteUser(id: String): Unit = Unit

    // FLOW — various element types
    fun observeStatus(): Flow<FixtureStatus> = flow {
        val statuses = FixtureStatus.entries
        var i = 0
        while (true) { emit(statuses[i++ % statuses.size]); delay(1_000) }
    }

    fun observeUser(): Flow<FixtureUser> = flow {
        var i = 0
        while (true) {
            emit(FixtureUser(
                id       = "live_$i",
                age      = 20 + i,
                score    = i * 0.1,
                active   = i % 2 == 0,
                byteFlag = (i % 127).toByte(),
                longId   = i.toLong() * 1_000,
                initial  = ('A' + i % 26),
                ratio    = i * 0.01f,
                status   = FixtureStatus.entries[i % 3],
                address  = FixtureAddress("$i Main St", "City $i", if (i % 2 == 0) "${i}000" else null),
                tags     = listOf("tag_$i", "live"),
                metadata = mapOf("tick" to i),
                aliases  = setOf("live_alias_$i"),
            ))
            i++
            delay(1_500)
        }
    }

    fun observeResult(): Flow<FixtureResult> = flow {
        var i = 0
        while (true) {
            val result = when (i % 3) {
                0 -> FixtureResult.Success(
                    user = FixtureUser("result_$i", 30, 9.9, true, 1, 999L, 'R', 1f,
                        FixtureStatus.ACTIVE, null, listOf("ok"), mapOf("code" to i), setOf()),
                    code = 200 + i,
                )
                1 -> FixtureResult.Empty
                else -> FixtureResult.Failure(message = "err_$i", retryable = i % 2 == 0)
            }
            emit(result)
            i++
            delay(2_000)
        }
    }

    fun observeAuthState(): Flow<FixtureAuthState> = flow {
        val states: List<FixtureAuthState> = listOf(
            FixtureAuthState.LoggedOut,
            FixtureAuthState.Refreshing,
            FixtureAuthState.LoggedIn(
                user  = FixtureUser("auth_user", 25, 5.0, true, 0, 0L, 'U', 0f,
                    FixtureStatus.ACTIVE, null, emptyList(), emptyMap(), emptySet()),
                token = "tok_fixture",
            ),
        )
        var i = 0
        while (true) { emit(states[i++ % states.size]); delay(2_000) }
    }

    fun observeList(): Flow<List<FixtureUser>> = flow {
        val base = FixtureUser("list_u", 21, 3.0, true, 0, 0L, 'L', 0f,
            FixtureStatus.PENDING, null, emptyList(), emptyMap(), emptySet())
        var i = 0
        while (true) {
            emit((0..i).map { base.copy(id = "item_$it", age = 21 + it) })
            i++
            delay(1_500)
        }
    }

    fun observeNullableString(): Flow<String?> = flow {
        var i = 0
        while (true) { emit(if (i % 2 == 0) "value_$i" else null); i++; delay(1_000) }
    }

    // FLOW with a parameter — start<Name> must thread it through to the flow call
    fun observeGreeting(prefix: String): Flow<String> = flow {
        var i = 0
        while (true) { emit("$prefix $i"); i++; delay(1_000) }
    }

    fun observeMap(): Flow<Map<String, FixtureUser>> = flow {
        val base = FixtureUser("map_u", 30, 7.5, true, 0, 0L, 'M', 0f,
            FixtureStatus.INACTIVE, null, emptyList(), emptyMap(), emptySet())
        var i = 0
        while (true) {
            emit(mapOf(
                "current"  to base.copy(id = "current_$i",  age = 30 + i),
                "previous" to base.copy(id = "previous_$i", age = 29 + i),
            ))
            i++
            delay(2_000)
        }
    }
}

// ── Concrete class — data/sealed/collection function parameters ───────────────

class FixtureParamsApi {
    fun saveUser(user: FixtureUser): FixtureUser = user
    fun saveNullableUser(user: FixtureUser?): String = user?.id ?: "null-user"
    fun describeResult(result: FixtureResult): String = when (result) {
        is FixtureResult.Success -> "success:${result.code}"
        is FixtureResult.Empty   -> "empty"
        is FixtureResult.Failure -> "failure:${result.message}"
        is FixtureResult.Partial -> "partial:${result.hint}"
    }
    fun saveAll(users: List<FixtureUser>): Int = users.size
    fun saveTeam(team: FixtureTeam): FixtureTeam = team
    fun tagUsers(usersByTag: Map<String, FixtureUser>): List<String> = usersByTag.keys.toList()
}

// ── Generic class — TypeParam in model ────────────────────────────────────────

/**
 * Generic class so the model contains [KmpTypeRef.TypeParam] entries.
 * Exercises T as return type, nullable T, and T inside a collection and Flow.
 */
class FixtureGenericApi<T> {
    @Suppress("UNCHECKED_CAST")
    fun get(): T = "generic_value" as T

    fun getOrNull(): T? = null
    fun wrap(): List<T> = emptyList()

    @Suppress("UNCHECKED_CAST")
    fun observe(): Flow<T> = flow {
        var i = 0
        while (true) { emit("generic_$i" as T); i++; delay(1_000) }
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun fetch(): T {
        delay(100)
        return "fetched_generic" as T
    }
}

// ── File-level properties ─────────────────────────────────────────────────────

val fixtureVersion: String = "1.0.0"
var fixtureMutableCounter: Int = 0
val fixtureActiveStatus: FixtureStatus = FixtureStatus.ACTIVE
val fixtureNullableUser: FixtureUser? = null

/** Flow-typed property — must be skipped loudly (no start/stop surface for property flows). */
val fixtureCounterStream: Flow<Int> = flow {
    var i = 0
    while (true) { emit(i++); delay(1_000) }
}

// ── File-level functions ──────────────────────────────────────────────────────

/** Typealias in a signature must resolve to its expansion (String), not the alias name. */
typealias FixtureUserId = String

fun fixtureEchoUserId(id: FixtureUserId): FixtureUserId = id

fun fixtureGreet(name: String): String = "Hello, $name"

fun fixtureDescribeShape(shape: FixtureShape): String = when (shape) {
    is FixtureCircle -> "circle:${shape.radius}"
    is FixtureSquare -> "square:${shape.side}"
}

fun fixturePay(payment: FixturePayment): String = when (payment) {
    is FixturePayment.Card -> "card:${payment.last4}"
    FixturePayment.Cash    -> "cash"
}

/** Extension function — the reader must skip it loudly (no receiver at the generated call site). */
fun String.fixtureShout(): String = uppercase()
fun fixtureAdd(a: Int, b: Int): Int = a + b
fun fixtureNullableEcho(value: String?): String? = value

suspend fun fixtureFetchUser(id: String): FixtureUser {
    delay(100)
    return FixtureUser(
        id       = id,
        age      = 30,
        score    = 5.0,
        active   = true,
        byteFlag = 1,
        longId   = 100L,
        initial  = 'F',
        ratio    = 1.0f,
        status   = FixtureStatus.ACTIVE,
        address  = null,
        tags     = emptyList(),
        metadata = emptyMap(),
        aliases  = emptySet(),
    )
}

suspend fun fixtureDeleteUser(id: String): Unit = Unit

// ── File-level flows ──────────────────────────────────────────────────────────

fun fixtureObserveCounter(): Flow<Int> = flow {
    var i = 0
    while (true) { emit(i++); delay(1_000) }
}

fun fixtureObserveStatus(): Flow<FixtureStatus> = flow {
    val statuses = FixtureStatus.entries
    var i = 0
    while (true) { emit(statuses[i++ % statuses.size]); delay(1_000) }
}

fun fixtureObserveNullableString(): Flow<String?> = flow {
    var i = 0
    while (true) { emit(if (i % 2 == 0) "value_$i" else null); i++; delay(1_000) }
}

// ── Interface/abstract return type fixture ─────────────────────────────────

class FixtureInterfaceApi {

    fun getRepository(): FixtureRepository = object : FixtureRepository {
        override fun findById(id: String): FixtureUser = FixtureUser(
            id = id, age = 25, score = 9.0, active = true, byteFlag = 1, longId = 100L,
            initial = 'R', ratio = 0.5f, status = FixtureStatus.ACTIVE, address = null,
            tags = listOf("repo"), metadata = mapOf("version" to 1), aliases = emptySet(),
        )
        override suspend fun fetchById(id: String): FixtureUser = findById(id)
        override suspend fun countAll(): Int = 42
        override suspend fun findByStatus(status: FixtureStatus): Int = if (status == FixtureStatus.ACTIVE) 1 else 0
        override fun observeAll(): Flow<List<FixtureUser>> = flow { emit(listOf(findById("all"))) }
    }

    fun getNullableRepository(): FixtureRepository? = null

    suspend fun fetchRepository(id: String): FixtureRepository = getRepository()

    fun getProcessor(): FixtureBaseProcessor = object : FixtureBaseProcessor() {
        override fun process(input: String): String = "processed:$input"
        override suspend fun processAsync(input: String): String = "async:$input"
    }

    fun getConfigured(): FixtureConfiguredProcessor = object : FixtureConfiguredProcessor("fixture") {
        override suspend fun run(input: String): String = "ran:$input"
    }

    fun processRepo(repo: FixtureRepository): String = repo.findById("fixture-param").id
    fun processNullableRepo(repo: FixtureRepository?): String =
        repo?.findById("fixture-nullable")?.id ?: "null-repo"
    suspend fun fetchFromRepo(repo: FixtureRepository, id: String): FixtureUser = repo.fetchById(id)
    fun processProcessor(processor: FixtureBaseProcessor): String = processor.process("hello")
}
