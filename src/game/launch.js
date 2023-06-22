import { VersionManager, get_system_info, ArgsManager } from "./config"
import { LibraryManager } from "./config"
import { spawn } from 'child_process'
import * as path from "path"

async function runGame(versionName, account, javaPath = null, ...args) {
  /**
   * Launches the game with the specified version, account, and optional Java path.
   *
   * @param {string} versionName - The name of the game version.
   * @param {Object} account - An object representing the user account.
   * @param {string|null} javaPath - The path to the Java executable.
   * If not provided, the default Java path is used.
   * @param {...string} args - Additional arguments to be passed to the game.
   * @returns {ChildProcess} - The child process representing the running game.
   */
  if (javaPath === null) javaPath = get_system_info().java_path

  const version = new VersionManager().getVersion(versionName)
  const libraryManager = new LibraryManager(version['id'])
  const gameDirectory = get_system_info()['home_path']
  const libraryPath = libraryManager.path()
  const classSep = ':'
  const versionJar = await libraryManager.download_client()
  let classpath = libraryManager.join_libraries(classSep, libraryPath)

  // Define custom arguments for launching the game.
  let custom_args = {
    'natives_directory': libraryManager.unzip_natives(),
    'launcher_name': 'leverage-launcher',
    'launcher_version': 20,
    'classpath': `${classpath}${classSep}${versionJar}`,
    'library_directory': libraryPath,
    'classpath_separator': classSep,
    'game_directory': gameDirectory,
    'assets_root': path.join(gameDirectory, 'assets'),
    'assets_index_name': version['assets'],
    'auth_uuid': account.uuid,
    'auth_access_token': account.token || '',
    'clientid': '', 'auth_xuid': '',
    'user_properties': '',
    'user_type': account.user_type,
    'version_type': version['type']
  }

  // Create an ArgsManager instance and generate processed arguments
  const argsManager = new ArgsManager(account.username, version['id'], custom_args)
  const processedArgs = argsManager.getArgs(version['mainClass'], ...args)
  const options = {shell: true, stdio: 'pipe', encoding: 'utf-8'}

  // Spawn a new Java process with the provided Java path and processed arguments
  return spawn(javaPath, processedArgs.split(' '), options)
}

export default runGame