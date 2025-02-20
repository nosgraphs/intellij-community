// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.diagnostic.startUpPerformanceReporter

import com.intellij.diagnostic.StartUpMeasurer
import com.intellij.ide.impl.ProjectUtilCore
import com.intellij.idea.IdeStarter
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.*
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.util.alsoIfNull
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

object FUSProjectHotStartUpMeasurer {

  enum class ProjectsType {
    Reopened, FromFilesToLoad, FromArgs, Unknown
  }

  enum class Violation {
    MightBeLightEditProject, MultipleProjects, NoProjectFound, WelcomeScreenShown, OpeningURI, ApplicationStarter, HasOpenedProject
  }

  /**
   * Might happen before [initializeAndGetStartUpContextElement]
   */
  fun splashBecameVisible() {
    FUSStartupReopenProjectMarkerElement.splashBecameVisible()
  }

  fun initializeAndGetStartUpContextElement(ideStarter: IdeStarter): CoroutineContext.Element? {
    if (ideStarter.isHeadless) {
      return null
    }
    if (ideStarter.javaClass !in listOf(IdeStarter::class.java, IdeStarter.StandaloneLightEditStarter::class.java)) {
      return null
    }
    FUSStartupReopenProjectMarkerElement.initialize()
    return FUSStartupReopenProjectMarkerElement
  }

  suspend fun getStartUpContextElementToPass(): CoroutineContext.Element? {
    return coroutineContext[FUSStartupReopenProjectMarkerElement]?.getStartUpContextElementToPass()
  }

  fun reportWelcomeScreenShown() {
    FUSStartupReopenProjectMarkerElement.reportWelcomeScreenShown()
  }

  suspend fun reportProjectType(projectsType: ProjectsType) {
    coroutineContext[FUSStartupReopenProjectMarkerElement]?.reportProjectType(projectsType)
  }

  /**
   * Reports the existence of project settings to filter cases of importing which may need more resources.
   */
  suspend fun reportProjectPath(projectFile: Path) {
    val marker = coroutineContext[FUSStartupReopenProjectMarkerElement] ?: return
    val hasSettings = withContext(Dispatchers.IO) { ProjectUtilCore.isValidProjectPath(projectFile) }
    marker.reportProjectSettings(hasSettings)
  }

  suspend fun resetProjectPath() {
    coroutineContext[FUSStartupReopenProjectMarkerElement]?.resetProjectSettings()
  }

  suspend fun openingMultipleProjects() {
    coroutineContext[FUSStartupReopenProjectMarkerElement]?.reportViolation(Violation.MultipleProjects)
  }

  suspend fun reportAlreadyOpenedProject() {
    coroutineContext[FUSStartupReopenProjectMarkerElement]?.reportViolation(Violation.HasOpenedProject)
  }

  fun noProjectFound() {
    FUSStartupReopenProjectMarkerElement.reportViolation(Violation.NoProjectFound)
  }

  fun lightEditProjectFound() {
    FUSStartupReopenProjectMarkerElement.reportViolation(Violation.MightBeLightEditProject)
  }

  suspend fun reportUriOpening() {
    coroutineContext[FUSStartupReopenProjectMarkerElement]?.reportViolation(Violation.OpeningURI)
  }

  fun reportStarterUsed() {
    FUSStartupReopenProjectMarkerElement.reportViolation(Violation.ApplicationStarter)
  }

  fun frameBecameVisible() {
    FUSStartupReopenProjectMarkerElement.reportFrameBecameVisible()
  }

  fun reportFrameBecameInteractive() {
    FUSStartupReopenProjectMarkerElement.reportFrameBecameInteractive()
  }

  fun markupRestored(file: VirtualFileWithId) {
    FUSStartupReopenProjectMarkerElement.reportMarkupRestored(file)
  }

  fun firstOpenedEditor(project: Project, file: VirtualFile, elementToPass: CoroutineContext.Element?) {
    if (elementToPass != FUSStartupReopenProjectMarkerElement) return
    service<MeasurerCoroutineService>().coroutineScope.launch {
      FUSStartupReopenProjectMarkerElement.reportFirstEditor(project, file, SourceOfSelectedEditor.TextEditor)
    }
  }

  suspend fun firstOpenedUnknownEditor(project: Project, file: VirtualFile) {
    coroutineContext[FUSStartupReopenProjectMarkerElement]?.reportFirstEditor(project, file, SourceOfSelectedEditor.UnknownEditor)
  }

  suspend fun openedReadme(project: Project, readmeFile: VirtualFile) {
    coroutineContext[FUSStartupReopenProjectMarkerElement]?.reportFirstEditor(project, readmeFile, SourceOfSelectedEditor.FoundReadmeFile)
  }

  fun reportNoMoreEditorsOnStartup(project: Project, startUpContextElementToPass: CoroutineContext.Element?) {
    if (startUpContextElementToPass == FUSStartupReopenProjectMarkerElement) {
      FUSStartupReopenProjectMarkerElement.reportNoMoreEditorsOnStartup(project)
    }
  }

  private object FUSStartupReopenProjectMarkerElement : CoroutineContext.Element, CoroutineContext.Key<FUSStartupReopenProjectMarkerElement> {
    private sealed interface Stage {
      data class VeryBeginning(val initialized: Boolean = false,
                               val splashBecameVisibleTime: Long? = null,
                               val projectType: ProjectsType = ProjectsType.Unknown,
                               val settingsExist: Boolean? = null,
                               val prematureFrameInteractive: PrematureFrameInteractiveData? = null,
                               val prematureEditorData: PrematureEditorStageData? = null) : Stage

      data class FrameVisible(val prematureEditorData: PrematureEditorStageData? = null, val settingsExist: Boolean?) : Stage
      data class FrameInteractive(val settingsExist: Boolean?) : Stage

      data class EditorStage(val data: PrematureEditorStageData, val settingsExist: Boolean?) : Stage {
        fun log(durationMillis: Long) {
          val eventData = data.getEventData()
          eventData.add(DURATION.with(durationMillis))
          if (settingsExist != null) {
            eventData.add(HAS_SETTINGS.with(settingsExist))
          }
          CODE_LOADED_AND_VISIBLE_IN_EDITOR_EVENT.log(data.project, eventData)
        }
      }

      data object Stopped : Stage
    }

    private data object PrematureFrameInteractiveData {
      fun log(duration: Long) {
        FRAME_BECAME_INTERACTIVE_EVENT.log(duration)
      }
    }

    private sealed interface PrematureEditorStageData {
      val project: Project
      fun getEventData(): MutableList<EventPair<*>>

      data class FirstEditor(override val project: Project,
                             val sourceOfSelectedEditor: SourceOfSelectedEditor,
                             val fileType: FileType,
                             val isMarkupLoaded: Boolean) : PrematureEditorStageData {
        override fun getEventData(): MutableList<EventPair<*>> {
          return mutableListOf(SOURCE_OF_SELECTED_EDITOR_FIELD.with(sourceOfSelectedEditor),
                               NO_EDITORS_TO_OPEN_FIELD.with(false),
                               EventFields.FileType.with(fileType),
                               LOADED_CACHED_MARKUP_FIELD.with(isMarkupLoaded))
        }
      }

      data class NoEditors(override val project: Project) : PrematureEditorStageData {
        override fun getEventData(): MutableList<EventPair<*>> {
          return mutableListOf(NO_EDITORS_TO_OPEN_FIELD.with(true))
        }
      }
    }

    private val stageLock = Object()

    @Volatile
    private var stage: Stage = Stage.VeryBeginning()

    private val markupResurrectedFileIds = IntOpenHashSet()

    override val key: CoroutineContext.Key<*>
      get() = this

    private fun <T> computeLocked(checkIsInitialized: Boolean = true, block: Stage.() -> T): T {
      synchronized(stageLock) {
        stage.apply {
          if (checkIsInitialized && this is Stage.VeryBeginning && !initialized) {
            stage = Stage.Stopped
            synchronized(markupResurrectedFileIds) { markupResurrectedFileIds.clear() }
          }
        }
        return stage.block()
      }
    }

    fun initialize() {
      computeLocked(false) {
        if (this is Stage.VeryBeginning && !initialized) {
          stage = Stage.VeryBeginning(true, splashBecameVisibleTime, projectType, settingsExist,
                                      prematureFrameInteractive, prematureEditorData)
        }
        else {
          stopReporting()
        }
      }
    }

    fun splashBecameVisible() {
      val nanoTime = System.nanoTime()
      // This may happen before we know about particulars in com.intellij.idea.IdeStarter.startIDE,
      // where initialization of FUSStartupReopenProjectMarkerElement happens.
      computeLocked(false) {
        if (this is Stage.VeryBeginning && splashBecameVisibleTime == null) {
          stage = Stage.VeryBeginning(initialized, nanoTime, projectType, settingsExist, prematureFrameInteractive, prematureEditorData)
        }
      }
    }

    fun reportViolation(violation: Violation) {
      computeLocked {
        if (this is Stage.VeryBeginning) {
          reportViolation(getDuration(), violation, splashBecameVisibleTime)
        }
      }
    }

    fun reportWelcomeScreenShown() {
      val welcomeScreenDuration = getDuration()
      computeLocked {
        if (this !is Stage.VeryBeginning) return@computeLocked
        if (splashBecameVisibleTime == null) {
          WELCOME_SCREEN_EVENT.log(DURATION.with(welcomeScreenDuration), SPLASH_SCREEN_WAS_SHOWN.with(false))
        }
        else {
          WELCOME_SCREEN_EVENT.log(DURATION.with(welcomeScreenDuration), SPLASH_SCREEN_WAS_SHOWN.with(true),
                                   SPLASH_SCREEN_VISIBLE_DURATION.with(getDuration(splashBecameVisibleTime)))
        }
        reportViolation(Violation.WelcomeScreenShown)
      }
    }

    fun reportProjectType(projectsType: ProjectsType) {
      computeLocked {
        if (this is Stage.VeryBeginning && projectType == ProjectsType.Unknown) {
          stage = Stage.VeryBeginning(initialized, splashBecameVisibleTime, projectsType, settingsExist, prematureFrameInteractive,
                                      prematureEditorData)
        }
      }
    }

    fun reportProjectSettings(exist: Boolean) {
      computeLocked {
        if (this is Stage.VeryBeginning && settingsExist == null) {
          stage = Stage.VeryBeginning(initialized, splashBecameVisibleTime, projectType, exist, prematureFrameInteractive,
                                      prematureEditorData)
        }
      }
    }

    fun resetProjectSettings() {
      computeLocked {
        if (this is Stage.VeryBeginning && settingsExist != null) {
          stage = Stage.VeryBeginning(initialized, splashBecameVisibleTime, projectType, null, prematureFrameInteractive,
                                      prematureEditorData)
        }
      }
    }

    fun reportFrameBecameVisible() {
      computeLocked {
        if (this !is Stage.VeryBeginning) {
          return@computeLocked
        }
        val duration = getDuration()

        reportFirstUiShownEvent(splashBecameVisibleTime, duration)

        if (settingsExist == null) {
          FRAME_BECAME_VISIBLE_EVENT.log(DURATION.with(duration), PROJECTS_TYPE.with(projectType))
        }
        else {
          FRAME_BECAME_VISIBLE_EVENT.log(DURATION.with(duration), PROJECTS_TYPE.with(projectType), HAS_SETTINGS.with(settingsExist))
        }

        if (prematureFrameInteractive != null) {
          prematureFrameInteractive.log(duration)
          if (prematureEditorData != null) {
            Stage.EditorStage(prematureEditorData, settingsExist).log(duration)
            stopReporting()
          }
          else {
            stage = Stage.FrameInteractive(settingsExist)
          }
        }
        else {
          stage = Stage.FrameVisible(prematureEditorData, settingsExist)
        }
      }
    }

    private fun reportViolation(duration: Long,
                                violation: Violation,
                                splashBecameVisibleTime: Long?) {
      reportFirstUiShownEvent(splashBecameVisibleTime, duration)
      FRAME_BECAME_VISIBLE_EVENT.log(DURATION.with(duration), VIOLATION.with(violation))
      stopReporting()
    }

    private fun reportFirstUiShownEvent(splashBecameVisibleTime: Long?, duration: Long) {
      splashBecameVisibleTime?.also {
        FIRST_UI_SHOWN_EVENT.log(getDuration(splashBecameVisibleTime), UIResponseType.Splash)
      }.alsoIfNull { FIRST_UI_SHOWN_EVENT.log(duration, UIResponseType.Frame) }
    }

    fun reportFrameBecameInteractive() {
      computeLocked {
        if (this is Stage.VeryBeginning && prematureFrameInteractive == null) {
          stage = Stage.VeryBeginning(initialized, splashBecameVisibleTime, projectType, settingsExist, PrematureFrameInteractiveData,
                                      prematureEditorData)
        }
        else if (this is Stage.FrameVisible) {
          val duration = getDuration()
          PrematureFrameInteractiveData.log(duration)
          if (prematureEditorData != null) {
            Stage.EditorStage(prematureEditorData, settingsExist).log(duration)
            stopReporting()
          }
          else {
            stage = Stage.FrameInteractive(settingsExist)
          }
        }
      }
    }

    private fun isMarkupLoaded(file: VirtualFile): Boolean {
      if (file !is VirtualFileWithId) return false
      return synchronized(markupResurrectedFileIds) { markupResurrectedFileIds.contains(file.id) }
    }

    suspend fun reportFirstEditor(project: Project, file: VirtualFile, sourceOfSelectedEditor: SourceOfSelectedEditor) {
      val durationMillis = System.nanoTime()

      withContext(Dispatchers.Default) {
        computeLocked {
          if (this is Stage.Stopped) return@computeLocked
          if (this is Stage.VeryBeginning && prematureEditorData != null) return@computeLocked
          if (this is Stage.FrameVisible && prematureEditorData != null) return@computeLocked

          val isMarkupLoaded = isMarkupLoaded(file)
          val fileType = ReadAction.nonBlocking<FileType> { return@nonBlocking file.fileType }.executeSynchronously()
          val editorStageData = PrematureEditorStageData.FirstEditor(project, sourceOfSelectedEditor, fileType, isMarkupLoaded)

          when (this) {
            is Stage.VeryBeginning -> {
              stage = Stage.VeryBeginning(initialized, splashBecameVisibleTime, projectType, settingsExist, prematureFrameInteractive,
                                          editorStageData)
            }
            is Stage.FrameVisible -> {
              stage = Stage.FrameVisible(editorStageData, settingsExist)
            }
            is Stage.FrameInteractive -> {
              stopReporting()
              Stage.EditorStage(editorStageData, settingsExist).log(durationMillis)
            }
            else -> {} //ignore
          }
        }
      }
    }

    private fun stopReporting() {
      computeLocked {
        stage = Stage.Stopped
        synchronized(markupResurrectedFileIds) { markupResurrectedFileIds.clear() }
      }
    }

    fun getStartUpContextElementToPass(): CoroutineContext.Element? {
      return computeLocked { return@computeLocked if (this == Stage.Stopped) null else this@FUSStartupReopenProjectMarkerElement }
    }

    fun reportNoMoreEditorsOnStartup(project: Project) {
      val durationMillis = System.nanoTime()
      val noEditorStageData = PrematureEditorStageData.NoEditors(project)
      computeLocked {
        when (this) {
          is Stage.Stopped -> return@computeLocked
          is Stage.VeryBeginning -> {
            if (prematureEditorData != null) return@computeLocked
            stage = Stage.VeryBeginning(initialized, splashBecameVisibleTime, projectType, settingsExist, prematureFrameInteractive,
                                        noEditorStageData)
          }
          is Stage.FrameVisible -> {
            if (prematureEditorData != null) return@computeLocked
            stage = Stage.FrameVisible(noEditorStageData, settingsExist)
          }
          is Stage.FrameInteractive -> {
            Stage.EditorStage(noEditorStageData, settingsExist).log(durationMillis)
            stopReporting()
          }
          else -> {} //ignore
        }
      }
    }

    fun reportMarkupRestored(file: VirtualFileWithId) {
      computeLocked {
        if (this == Stage.Stopped) {
          return@computeLocked
        }
        synchronized(markupResurrectedFileIds) { markupResurrectedFileIds.add(file.id) }
      }
    }
  }
}

fun getDuration(finishTimestampNano: Long = System.nanoTime()): Long {
  return TimeUnit.NANOSECONDS.toMillis(finishTimestampNano - StartUpMeasurer.getStartTime())
}

private val WELCOME_SCREEN_GROUP = EventLogGroup("welcome.screen.startup.performance", 1)

private val SPLASH_SCREEN_WAS_SHOWN = EventFields.Boolean("splash_screen_was_shown")
private val SPLASH_SCREEN_VISIBLE_DURATION = LongEventField("splash_screen_became_visible_duration_ms")
private val DURATION = EventFields.DurationMs
private val WELCOME_SCREEN_EVENT = WELCOME_SCREEN_GROUP.registerVarargEvent("welcome.screen.shown",
                                                                            DURATION, SPLASH_SCREEN_WAS_SHOWN,
                                                                            SPLASH_SCREEN_VISIBLE_DURATION)

class WelcomeScreenPerformanceCollector : CounterUsagesCollector() {
  override fun getGroup(): EventLogGroup = WELCOME_SCREEN_GROUP
}

private val GROUP = EventLogGroup("reopen.project.startup.performance", 1)

private enum class UIResponseType {
  Splash, Frame
}

private val UI_RESPONSE_TYPE = EventFields.Enum("type", UIResponseType::class.java)
private val FIRST_UI_SHOWN_EVENT: EventId2<Long, UIResponseType> = GROUP.registerEvent("first.ui.shown", DURATION, UI_RESPONSE_TYPE)

private val PROJECTS_TYPE: EnumEventField<FUSProjectHotStartUpMeasurer.ProjectsType> =
  EventFields.Enum("projects_type", FUSProjectHotStartUpMeasurer.ProjectsType::class.java)
private val HAS_SETTINGS: BooleanEventField = EventFields.Boolean("has_settings")
private val VIOLATION: EnumEventField<FUSProjectHotStartUpMeasurer.Violation> =
  EventFields.Enum("violation", FUSProjectHotStartUpMeasurer.Violation::class.java)
private val FRAME_BECAME_VISIBLE_EVENT = GROUP.registerVarargEvent("frame.became.visible",
                                                                   DURATION, HAS_SETTINGS, PROJECTS_TYPE, VIOLATION)

private val FRAME_BECAME_INTERACTIVE_EVENT = GROUP.registerEvent("frame.became.interactive", DURATION)

private enum class SourceOfSelectedEditor {
  TextEditor, UnknownEditor, FoundReadmeFile
}

private val LOADED_CACHED_MARKUP_FIELD = EventFields.Boolean("loaded_cached_markup")
private val SOURCE_OF_SELECTED_EDITOR_FIELD: EnumEventField<SourceOfSelectedEditor> =
  EventFields.Enum("source_of_selected_editor", SourceOfSelectedEditor::class.java)
private val NO_EDITORS_TO_OPEN_FIELD = EventFields.Boolean("no_editors_to_open")
private val CODE_LOADED_AND_VISIBLE_IN_EDITOR_EVENT = GROUP.registerVarargEvent("code.loaded.and.visible.in.editor", DURATION,
                                                                                EventFields.FileType,
                                                                                HAS_SETTINGS,
                                                                                LOADED_CACHED_MARKUP_FIELD,
                                                                                NO_EDITORS_TO_OPEN_FIELD, SOURCE_OF_SELECTED_EDITOR_FIELD)

internal class HotProjectReopenStartUpPerformanceCollector : CounterUsagesCollector() {
  override fun getGroup(): EventLogGroup {
    return GROUP
  }
}

@Service
private class MeasurerCoroutineService(val coroutineScope: CoroutineScope)