# How several Claude sessions work on this project

One repo: `D:\pdf\SmartDocs-project-v0.4.0`. GitHub: https://github.com/maikljone22-beep/pdf-tools-and-reader-and-editor-free-2

## Rules
1. **Own branch + own worktree per session.** Never commit to `main` directly (only the integrator does).
   `git -C D:\pdf\SmartDocs-project-v0.4.0 worktree add D:\pdf\<session>-wt -b <type>/<topic> main`
   then copy `local.properties` into the worktree. Branch types: `fix/…`, `feature/…`, `audit/…`.
2. **Never build in another session's folder.** Build only in your own worktree. One heavy build at a time on this PC
   if memory is short (16 GB); prefer GitHub builds for full APKs.
3. **Push your branch** when a stage is done: `git push -u origin <branch>`.
   GitHub Actions builds every branch and uploads an APK named `SmartDocs-<branch>-<run>` (Actions tab → run → Artifacts).
   If tests fail, the test report is uploaded as `test-reports-<run>`.
4. **Before asking for a merge:** `git fetch origin && git rebase origin/main`, then `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
   in your worktree, push, wait for a green GitHub build.
5. **Merging into `main` is done by one integrator session (currently `pdf-ad`).** Send it a message with the branch name
   and the green run link. It merges, pushes `main`, and tells the others to rebase.
6. **Shared hot files** (`ui/reader/*`, `MainActivity.kt`, `ui/Navigation.kt`, `ui/tools/ToolScreens.kt`, `ui/tools/ToolsScreen.kt`,
   `app/build.gradle.kts`, `AndroidManifest.xml`): announce to the integrator before editing; keep edits small and marked.
7. **Never commit secrets**: `release.jks`, `keystore.properties`, `*.properties` with keys are git-ignored. Keep it that way.
8. LF line endings (`.gitattributes`), no raw invisible Unicode in Kotlin source, Arabic strings in `values/`, English in `values-en/`.
