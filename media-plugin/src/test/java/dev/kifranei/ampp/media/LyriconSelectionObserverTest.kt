package dev.kifranei.ampp.media

import org.junit.Assert.*
import org.junit.Test

class LyriconSelectionObserverTest {
    @Test fun `host observer replays stored selection and handles changes until removed`() {
        val model = Model()
        val output = mutableListOf<Boolean>()
        val remove = observeLyriconSelection(model, Model::class.java.getMethod("getSelectionSource"),
            Observer::class.java, output::add)
        assertEquals(listOf(true), output)
        model.selected.emit(false)
        model.selected.emit(null)
        model.selected.emit("true")
        assertEquals(listOf(true, false, false, false), output)
        remove()
        model.selected.emit(true)
        assertEquals(4, output.size)
        assertTrue(model.selected.observers.isEmpty())
    }

    @Test fun `failed registration removes a partially registered observer`() {
        val model = Model()
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            observeLyriconSelection(model, Model::class.java.getMethod("getSelectionSource"),
                Observer::class.java) { error("callback failed") }
        }
        assertTrue(model.selected.observers.isEmpty())
    }

    interface Observer { fun onChanged(value: Any?) }
    class Source {
        val observers = HashSet<Observer>()
        fun observeForever(observer: Observer) { observers += observer; observer.onChanged(true) }
        fun removeObserver(observer: Observer) { observers -= observer }
        fun emit(value: Any?) { observers.toList().forEach { it.onChanged(value) } }
    }
    class Model {
        val selected = Source()
        fun getSelectionSource() = selected
    }
}
