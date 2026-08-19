package io.bluewallet.blueberry.bus

fun createMessageBus(): MessageBus {
    val listeners = mutableMapOf<Event<*>, MutableSet<Any>>()

    return object : MessageBus {
        override fun <T> on(event: Event<T>, handler: (T) -> Unit): () -> Unit {
            val set = listeners.getOrPut(event) { mutableSetOf() }
            set.add(handler)
            return {
                set.remove(handler)
            }
        }

        override fun <T> emit(event: Event<T>, payload: T) {
            val set = listeners[event] ?: return
            for (handler in set.toList()) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (handler as (T) -> Unit)(payload)
                } catch (_: Throwable) {
                    // isolate subscriber failures
                }
            }
        }
    }
}
