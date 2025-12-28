package top.e404.eapi.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.openjdk.nashorn.api.scripting.AbstractJSObject
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory
import org.openjdk.nashorn.internal.objects.Global
import org.openjdk.nashorn.internal.runtime.ConsString
import org.openjdk.nashorn.internal.runtime.ECMAErrors
import org.openjdk.nashorn.internal.runtime.ScriptRuntime
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import top.e404.eapi.PL
import top.e404.eapi.config.Config
import top.e404.eapi.config.RouterConfig
import java.io.File
import javax.script.ScriptEngine
import javax.script.ScriptException
import javax.script.SimpleBindings

object HttpServer {
    private val factory = NashornScriptEngineFactory()
    var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    // http server

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    fun start() {
        val config = Config.config
        if (!config.server.enable) return
        val scriptEngine = factory.getScriptEngine(
            config.nashornParams.toTypedArray(),
            PL.javaClass.classLoader
        )
        server = embeddedServer(CIO, config.server.port) {
            install(CallLogging) {
                level = Level.INFO
                logger = LoggerFactory.getLogger("Ktor")
            }
            routing {
                for (routerConfig in config.routers) {
                    val method = when (routerConfig.method) {
                        "GET" -> HttpMethod.Get
                        "POST" -> HttpMethod.Post
                        "PATCH" -> HttpMethod.Patch
                        "PUT" -> HttpMethod.Put
                        else -> {
                            log.warn("跳过不支持的路由方法: ${routerConfig.method}")
                            continue
                        }
                    }
                    PL.debug { "注册 ${routerConfig.path}" }
                    configureRoute(routerConfig, method, scriptEngine)
                }
            }
        }.also { it.start(false) }
    }

    // script exec

    @Suppress("UNUSED")
    object ScriptConsole {
        fun log(vararg args: Any?) = info(*args)
        fun info(vararg args: Any?) = PL.debug(format("info", *args))
        fun warn(vararg args: Any?) = PL.debug(format("warn", *args))
        fun error(vararg args: Any?) = PL.debug(format("error", *args))
        fun debug(vararg args: Any?) = PL.debug(format("debug", *args))
        private fun format(vararg args: Any?) = args.joinToString(separator = " ") { it.toString() }
    }

    class ScriptBizException(val code: Int, override val message: String) : RuntimeException(message)


    object Fail : AbstractJSObject() {
        override fun call(thiz: Any?, vararg args: Any?) = throw ScriptBizException(args[0] as Int, args[1] as String)
    }
    object Require : AbstractJSObject() {
        private fun defineJsFile(src: String): File {
            val target = File(PL.dataFolder, src)
            if (!target.exists() && !src.endsWith(".js")) {
                // 让用户可选输入 .js 后缀名
                val alternative = File(PL.dataFolder, "$src.js")
                if (alternative.exists()) {
                    return alternative
                }
            }
            return target
        }
        override fun call(thiz: Any, vararg args: Any): Any {
            val from = args[0]
            val src = if (from is ConsString) from.toString() else from
            if (src is String) {
                val file = defineJsFile(src)
                return Global.load(thiz, file)
            }
            throw ECMAErrors.typeError("not.a.string", ScriptRuntime.safeToString(from))
        }
    }

    private fun Routing.configureRoute(
        routerConfig: RouterConfig,
        method: HttpMethod,
        scriptEngine: ScriptEngine
    ) = route(routerConfig.path, method) {
        handle {
            try {
                // 注入查询参数
                val bindings = SimpleBindings(
                    mutableMapOf<String, Any>(
                        "console" to ScriptConsole,
                        "fail" to Fail,
                        "require" to Require,
                        "pathParameters" to call.pathParameters.entries().associate {
                            it.key to it.value.firstOrNull()
                        },
                        "queryParameters" to call.queryParameters.entries().associate {
                            it.key to it.value.firstOrNull()
                        },
                    )
                )
                val result = scriptEngine.eval(routerConfig.script, bindings).toString()
                call.respondText(result, ContentType.Application.Json)
            } catch (e: ScriptBizException) {
                call.respondText(
                    e.message,
                    ContentType.Text.Plain,
                    HttpStatusCode.fromValue(e.code)
                )
            } catch (e: ScriptException) {
                PL.warn("执行路由脚本时发生错误", e)
                call.respondText(
                    "执行脚本发生错误: ${e.message}",
                    ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError
                )
            }
        }
    }
}