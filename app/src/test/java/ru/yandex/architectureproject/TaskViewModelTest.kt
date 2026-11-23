package ru.yandex.architectureproject

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.yandex.architectureproject.data.db.TaskDao
import ru.yandex.architectureproject.data.model.Task
import ru.yandex.architectureproject.data.repository.TaskRepository
import ru.yandex.architectureproject.domain.*
import ru.yandex.architectureproject.presentation.state.TaskAction
import ru.yandex.architectureproject.presentation.state.TaskState
import ru.yandex.architectureproject.presentation.viewmodel.TaskViewModel

@OptIn(ExperimentalCoroutinesApi::class)
class TaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val dao = FakeTaskDao()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TaskViewModel {
        val repository = TaskRepository(dao)
        return TaskViewModel(
            AddTaskUseCase(repository), DeleteTaskUseCase(repository),
            GetAllTasksUseCase(repository), CompleteTaskUseCase(repository),
            IncompleteTaskUseCase(repository), dispatcher
        ).also { store.put("tasks", it) }
    }

    @Test
    fun `load add update and delete tasks keep one subscription`() = runTest {
        val vm = createViewModel()
        runCurrent()
        assertEquals(TaskState.Loaded(emptyList()), vm.state.value)
        vm.reduce(TaskAction.AddTask("Купить хлеб"))
        runCurrent()
        val task = dao.tasks.value.single()
        assertEquals("Купить хлеб", task.text)
        vm.reduce(TaskAction.UpdateTaskStatus(task.id, true))
        runCurrent()
        assertTrue(dao.tasks.value.single().isDone)
        vm.reduce(TaskAction.UpdateTaskStatus(task.id, false))
        runCurrent()
        assertFalse(dao.tasks.value.single().isDone)
        vm.reduce(TaskAction.DeleteTask(task.id))
        runCurrent()
        assertEquals(TaskState.Loaded(emptyList()), vm.state.value)
        assertEquals(1, dao.subscriptions)
    }

    @Test
    fun `repository errors become UI errors`() = runTest {
        val vm = createViewModel()
        runCurrent()
        dao.failWrites = true
        vm.reduce(TaskAction.AddTask("Задача"))
        runCurrent()
        assertEquals(TaskState.Error("Ошибка записи"), vm.state.value)
    }

    @Test
    fun `loading failure can be retried`() = runTest {
        dao.failReads = true
        val vm = createViewModel()
        runCurrent()
        assertEquals(TaskState.Error("Ошибка чтения"), vm.state.value)
        dao.failReads = false
        vm.reduce(TaskAction.LoadTasks)
        runCurrent()
        assertEquals(TaskState.Loaded(emptyList()), vm.state.value)
    }

    @Test
    fun `completed task is deleted only after ten seconds`() = runTest {
        val vm = createViewModel()
        vm.reduce(TaskAction.AddTask("Задача"))
        runCurrent()
        val id = dao.tasks.value.single().id
        vm.reduce(TaskAction.UpdateTaskStatus(id, true))
        runCurrent()
        assertTrue(dao.tasks.value.single().isDone)
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, dao.tasks.value.size)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(dao.tasks.value.isEmpty())
        assertEquals(TaskState.Loaded(emptyList()), vm.state.value)
    }

    private class FakeTaskDao : TaskDao {
        val tasks = MutableStateFlow<List<Task>>(emptyList())
        var subscriptions = 0
        var failWrites = false
        var failReads = false
        private var nextId = 1

        override fun getAllTasks(): Flow<List<Task>> = flow {
            subscriptions++
            check(!failReads) { "Ошибка чтения" }
            emitAll(tasks)
        }

        override suspend fun addTask(task: Task) {
            check(!failWrites) { "Ошибка записи" }
            tasks.value += task.copy(id = nextId++)
        }

        override suspend fun updateTaskStatus(taskId: Int, isDone: Boolean) {
            tasks.value = tasks.value.map { if (it.id == taskId) it.copy(isDone = isDone) else it }
        }

        override suspend fun deleteTask(taskId: Int) {
            tasks.value = tasks.value.filterNot { it.id == taskId }
        }
    }
}
