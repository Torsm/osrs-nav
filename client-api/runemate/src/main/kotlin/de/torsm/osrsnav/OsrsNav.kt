package de.torsm.osrsnav

import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.google.gson.typeadapters.RuntimeTypeAdapterFactory
import com.runemate.game.api.hybrid.entities.definitions.GameObjectDefinition
import com.runemate.game.api.hybrid.local.Skill
import com.runemate.game.api.hybrid.local.Varbits
import com.runemate.game.api.hybrid.local.Varps
import com.runemate.game.api.hybrid.local.hud.interfaces.Equipment
import com.runemate.game.api.hybrid.local.hud.interfaces.Inventory
import com.runemate.game.api.hybrid.local.hud.interfaces.SpriteItem
import com.runemate.game.api.hybrid.location.Coordinate
import com.runemate.game.api.hybrid.location.navigation.web.WebPath
import com.runemate.game.api.hybrid.location.navigation.web.vertex_types.CoordinateVertex
import com.runemate.game.api.hybrid.location.navigation.web.vertex_types.objects.BasicObjectVertex
import com.runemate.game.api.hybrid.location.navigation.web.vertex_types.teleports.BasicItemTeleportVertex
import com.runemate.game.api.hybrid.location.navigation.web.vertex_types.teleports.TeleportSpellVertex
import com.runemate.game.api.hybrid.region.GameObjects
import com.runemate.game.api.osrs.local.hud.interfaces.Magic
import java.lang.reflect.Type
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.regex.Pattern

object OsrsNav {
    private const val NAV_URL = "http://localhost:8000"

    private val httpClient by lazy {
        HttpClient.newBuilder().build()
    }

    private val gson by lazy {
        val edgeTypeAdapterFactory = RuntimeTypeAdapterFactory.of(Edge::class.java)
            .registerSubtype(Step::class.java)
            .registerSubtype(Door::class.java)
            .registerSubtype(GameObjectEdge::class.java, "GameObject")
            .registerSubtype(SpellTeleport::class.java)
            .registerSubtype(ItemTeleport::class.java)

        GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeAdapterFactory(edgeTypeAdapterFactory)
            .registerTypeAdapter(Pattern::class.java, PatternTypeAdapter)
            .create()
    }

    private var dataSelection_: DataSelection? = null
    val dataSelection: DataSelection?
        get() {
            return dataSelection_ ?: run {
                val httpRequest = HttpRequest.newBuilder()
                    .uri(URI("$NAV_URL/select"))
                    .GET()
                    .build()
                doHttpRequest<DataSelection>(httpRequest)?.also {
                    dataSelection_ = it
                }
            }
        }

    private fun doRequest(request: PathGenerationRequest): List<Edge>? {
        val json = gson.toJson(request)
        val httpRequest = HttpRequest.newBuilder()
            .uri(URI("$NAV_URL/path"))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build()
        return doHttpRequest(httpRequest, TypeToken.getParameterized(List::class.java, Edge::class.java).type)
    }

    fun buildBetween(start: Coordinate, end: Coordinate, gameState: GameState = GameState.fromGame()): List<Edge>? {
        val request = PathGenerationRequest(start, end, gameState)
        return doRequest(request)
    }

    private inline fun <reified T> doHttpRequest(request: HttpRequest, type: Type = T::class.java): T? = try {
        val httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (httpResponse.statusCode() == 200) {
            httpResponse.body().bufferedReader().use { reader ->
                gson.fromJson<T>(reader, type)
            }
        } else {
            println(httpResponse.body().bufferedReader().use { it.readText() })
            null
        }
    } catch (ex: Exception) {
        ex.printStackTrace()
        null
    }
}

data class PathGenerationRequest(
    val start: Coordinate,
    val end: Coordinate,
    val gameState: GameState,
)

object PatternTypeAdapter : TypeAdapter<Pattern>() {
    override fun write(writer: JsonWriter, pattern: Pattern?) {
        if (pattern == null) {
            writer.nullValue()
        } else {
            writer.value(pattern.pattern())
        }
    }

    override fun read(reader: JsonReader): Pattern? {
        return if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            null
        } else if (reader.peek() == JsonToken.STRING) {
            Pattern.compile(reader.nextString())
        } else {
            throw UnsupportedOperationException("Token is not a regex pattern: " + reader.peek())
        }
    }
}

data class DataSelection(
    val varps: List<Int>,
    val varbits: List<Int>,
    val items: List<Pattern>,
    val skills: List<String>,
)

data class GameState(
    val varps: Map<Int, Int>,
    val varbits: Map<Int, Int>,
    val items: Map<String, Int>,
    val skills: Map<String, Int>,
) {
    companion object {
        fun fromGame(): GameState {
            val varps = OsrsNav.dataSelection?.varps?.let { varps ->
                varps.associateWith { Varps.getAt(it).value }
            } ?: mapOf()

            val varbits = OsrsNav.dataSelection?.varbits?.let { varbits ->
                varbits.asSequence().mapNotNull { Varbits.load(it) }.associate { it.id to it.value }
            } ?: mapOf()

            val spriteItems = OsrsNav.dataSelection?.items?.let { items ->
                Inventory.newQuery().names(items).results().asList() + Equipment.newQuery().names(items).results().asList()
            } ?: let {
                Inventory.newQuery().results().asList() + Equipment.newQuery().results().asList()
            }
            val items = spriteItems.associate { (it.definition?.name ?: "null") to it.quantity }

            val skills = OsrsNav.dataSelection?.skills?.let { skills ->
                skills.associateWith { Skill.valueOf(it).currentLevel }
            } ?: let {
                Skill.values().asSequence().filter { it.currentLevel >= 0 }.associate { it.name to it.currentLevel }
            }

            return GameState(varps, varbits, items, skills)
        }
    }
}

fun convert(path: List<Edge>): WebPath {
    val mapped = path.map {
        when (it) {
            is Door -> BasicObjectVertex(it.position, GameObjectDefinition.get(it.id)?.name, it.action, listOf())
            is GameObjectEdge -> BasicObjectVertex(it.position, GameObjectDefinition.get(it.id)?.name, it.action, listOf())
            is ItemTeleport -> BasicItemTeleportVertex(Coordinate(0), SpriteItem.Origin.INVENTORY, it.item, it.action, listOf())
            is SpellTeleport -> TeleportSpellVertex(Magic.valueOf(it.spell.uppercase().replace(' ', '_')), Coordinate(0), listOf())
            is Step -> CoordinateVertex(it.position, listOf())
        }
    }
    return WebPath(mapped, path.size.toDouble())
}
