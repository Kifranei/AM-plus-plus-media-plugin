package dev.kifranei.ampp.media

import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Observe host LiveData without mixing the module's and host's AndroidX classes. */
internal fun observeLyriconSelection(
    model: Any,
    getter: Method,
    observerType: Class<*>,
    onChanged: (Boolean) -> Unit,
): () -> Unit {
    val source = checkNotNull(getter.invoke(model))
    val observe = getter.returnType.getMethod("observeForever", observerType)
    val remove = getter.returnType.getMethod("removeObserver", observerType)
    val observer = Proxy.newProxyInstance(observerType.classLoader, arrayOf(observerType)) { proxy, method, args ->
        when (method.name) {
            "onChanged" -> { onChanged(args?.firstOrNull() == true); null }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "AM++ Lyricon selection observer"
            else -> null
        }
    }
    try {
        observe.invoke(source, observer)
    } catch (error: Throwable) {
        runCatching { remove.invoke(source, observer) }
        throw error
    }
    return { remove.invoke(source, observer); Unit }
}
