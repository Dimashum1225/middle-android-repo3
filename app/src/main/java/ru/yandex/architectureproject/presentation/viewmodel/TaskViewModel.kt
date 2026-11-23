package ru.yandex.architectureproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yandex.architectureproject.domain.AddTaskUseCase
import ru.yandex.architectureproject.domain.CompleteTaskUseCase
import ru.yandex.architectureproject.domain.DeleteTaskUseCase
import ru.yandex.architectureproject.domain.GetAllTasksUseCase
import ru.yandex.architectureproject.domain.IncompleteTaskUseCase
import ru.yandex.architectureproject.presentation.state.TaskAction
import ru.yandex.architectureproject.presentation.state.TaskState

class TaskViewModel(
    private val addTaskUseCase: AddTaskUseCase,
    private val deleteTaskUseCase: DeleteTaskUseCase,
    private val getAllTasksUseCase: GetAllTasksUseCase,
    private val completeTaskUseCase: CompleteTaskUseCase,
    private val incompleteTaskUseCase: IncompleteTaskUseCase,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _state = MutableStateFlow<TaskState>(TaskState.Loading)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private val taskJobs = mutableMapOf<Int, Job>()

    init {
        reduce(TaskAction.LoadTasks)
    }

    fun reduce(action: TaskAction) {
        when (action) {
            TaskAction.LoadTasks -> {
                // Room сам публикует изменения; не создаём повторные подписки.
                if (loadJob?.isActive == true) return
                loadJob = viewModelScope.launch { loadTasks() }
            }
            is TaskAction.AddTask -> changeTasks { addTaskUseCase(action.text) }
            is TaskAction.DeleteTask -> changeTask(action.taskId) { deleteTaskUseCase(action.taskId) }
            is TaskAction.UpdateTaskStatus -> changeTask(action.taskId) {
                if (action.isDone) {
                    completeTaskUseCase(action.taskId)
                } else {
                    incompleteTaskUseCase(action.taskId)
                }
            }
        }
    }

    private fun changeTask(taskId: Int, change: suspend () -> Unit) {
        val previousJob = taskJobs.remove(taskId)
        previousJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // Дожидаемся отмены предыдущего изменения этой же задачи.
            previousJob?.join()
            performChange(change)
        }
        taskJobs[taskId] = job
        job.invokeOnCompletion {
            if (taskJobs[taskId] === job) taskJobs.remove(taskId)
        }
        job.start()
    }

    private fun changeTasks(change: suspend () -> Unit) {
        viewModelScope.launch { performChange(change) }
    }

    private suspend fun performChange(change: suspend () -> Unit) {
        try {
            withContext(ioDispatcher) { change() }
            reduce(TaskAction.LoadTasks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = TaskState.Error(e.message ?: "Ошибка изменения задачи")
        }
    }

    private suspend fun loadTasks() {
        withContext(ioDispatcher) {
            getAllTasksUseCase()
                .distinctUntilChanged()
                .onStart { _state.value = TaskState.Loading }
                .catch { e -> _state.value = TaskState.Error(e.message ?: "Ошибка загрузки") }
                .collect { tasks -> _state.value = TaskState.Loaded(tasks) }
        }
    }
}
