import { check_sha1 } from "./files"

const { execSync } = require('child_process')
const path = require('path')
const os = require("os")
const fs = require("fs")
const crypto = require('crypto')
const AdmZip  = require('adm-zip')

const default_jvm = [
  {
    "rules": [{"action": "allow", "features": {"has_custom_resolution": true}}],
    "value": ["--width", "${resolution_width}", "--height", "${resolution_height}"]
  },
  "-Djava.library.path=${natives_directory}",
  "-Dminecraft.launcher.brand=${launcher_name}",
  "-Dminecraft.launcher.version=${launcher_version}",
  "-cp",
  "${classpath}"
]

const AUTH_URL = "https://authserver.mojang.com/authenticate"
const VERSIONS = "https://launchermeta.mojang.com/mc/game/version_manifest.json"
const SETTINGS = 'leverage_settings.json'

function java_linux_path() {
  // The function javaLinuxPath retrieves the path to
  // the Java installation on a Linux system.
  const java_path = execSync('which java').toString()
  return path.resolve(java_path.trim())
}

function get_system_info() {
  // Retrieves system information.
  let os_name = (os.platform() === 'win32')? 'windows' : os.platform()
  const systemInfo = {
    os_name: os_name,
    home_path: path.join(os.homedir(), '.minecraft'),
    java_path: process.env.JAVA_HOME || '',
    arch: 'x' + process.arch.slice(-2),
    release: os.release()
  }

  if (systemInfo.os_name === 'linux') {
    // For Linux systems, retrieve the Java
    // path using a specific function
    systemInfo.java_path = java_linux_path()
  }

  return systemInfo
}

function get_settings() {
  /**
   * Returns the launcher settings and if file
   * does not exist create a new configuration
   */
  if (!fs.existsSync(SETTINGS)) {
    const fileData = {}
    const data = JSON.stringify(fileData, null, 2)
    fs.writeFileSync(SETTINGS, data)
  }
  const fileContent = fs.readFileSync(SETTINGS, 'utf-8')
  return JSON.parse(fileContent)
}

function set_option(key, value) {
  let data = get_settings(); data[key] = value
  let str = JSON.stringify(data, null, 2)
  fs.writeFileSync(SETTINGS, str); return data
}

function unzip_file(file_path, destination = '', omitted = []) {
  /**
   * Unzips a file to the specified destination path, excluding any files
   * specified in the 'omitted' array.
   */
  let zip = new AdmZip(file_path)
  let zip_entries = zip.getEntries()
  let file_list = []

  zip_entries.forEach((zip_entry) => {
    let file_name = zip_entry.entryName, is_excluded = false
    let dest_path = destination + path.sep + file_name

    // Check if the file is omitted
    omitted.forEach((key) => {if (file_name.includes(key)) is_excluded = true})

    if (!zip_entry.entryName.includes(omitted)) {
      if (!zip_entry.isDirectory) {
        fs.writeFileSync(dest_path, zip_entry.getData())
        file_list.push(dest_path)
      } else if (zip_entry.isDirectory && !fs.existsSync(dest_path)) {
        fs.mkdirSync(dest_path)
      }
    }
  })
  return file_list
}

class VersionManager {
  constructor(url) {
    this.defaultPath = get_system_info().home_path;
    this.defaultUrl = url || VERSIONS;
    this.fakePiston = 'http://localhost:7000';
  }

  localVersion(versionName) {
    /** Retrieves local version data from a given version name. */
    const fileName = `${versionName}/${versionName}.json`
    const basePath = path.join(this.defaultPath, 'versions', fileName)
    if (!fs.existsSync(basePath)) return {}
    const fileContent = fs.readFileSync(basePath, 'utf-8')
    return JSON.parse(fileContent)
  }

  localVersions() {
    /**
     * List all versions in the local storage using
     * the same syntax that launchermeta.mojang.com
     */
    const versionPath = path.join(this.defaultPath, 'versions');
    const versions = [];
    const files = fs.readdirSync(versionPath);
    // Walks all versions and save the specific params.
    for (const version of files) {
      const url = `${this.fakePiston}/versions/${version}.json`;
      const versionData = this.localVersion(version);
      const params = ['id', 'type', 'time', 'releaseTime', 'url'];
      const filteredVersion = Object.fromEntries(
        Object.entries(versionData).filter(([key]) => params.includes(key))
      );
      versions.push({ url, ...filteredVersion });
    }
    return { versions, latest: [] };
  }

  async download(versionName, url) {
    /** Download de version.json from a specific url. */
    // Get the specific version and check if exist
    const data = this.localVersion(versionName);
    if (Object.keys(data).length !== 0) return data;
    // Set the versions path and the versions url.
    const versionPath = path.join(this.defaultPath, 'versions');
    url = url || VERSIONS;
    const versions = await (await fetch(url)).json();
    const version = versions.find((ver) => ver.id === versionName);
    if (!version) return null;
    // Create the version folder and then get the version details
    const versionFolderPath = path.join(versionPath, version.id);
    if (!fs.existsSync(versionFolderPath)) fs.mkdirSync(versionFolderPath);
    const filePath = path.join(versionFolderPath, `${versionName}.json`);
    const versionData = await (await fetch(version.url)).json();
    // Write the version details in the correspondent folder
    let args = [versionData, null, 2]
    fs.writeFileSync(filePath, JSON.stringify(...args));
    if (versionData.inheritsFrom) {
      await this.download(versionData.inheritsFrom, url);
    }
    return versionData;
  }

  updateLegacy(version) {
    /**
     * Update the legacy version arguments to an updated format
     */
    if (version.arguments) return version.arguments
    const args = version.minecraftArguments
    return { game: args.split(' '), jvm: default_jvm }
  }

  getInheritance(version) {
    /** Get the missing keys from the inherit version */
    if (!version.inheritsFrom) return {}
    const baseVersion = this.localVersion(version.inheritsFrom)
    const keys = Object.keys(baseVersion).filter((key) => !version[key])
    return Object.fromEntries(keys.map((key) => [key, baseVersion[key]]))
  }

  getVersion(versionName) {
    /** Retrieves and processes the data for a specific version */
    const version = this.localVersion(versionName);
    const args = this.updateLegacy(version);
    version.arguments = args;
    Object.assign(version, this.getInheritance(version));
    // if the version is inherits from another version
    // concatenates the arguments
    if (version.inheritsFrom) {
      const inherits = this.getVersion(version.inheritsFrom);
      inherits.arguments = this.updateLegacy(inherits);
      for (const [key, value] of Object.entries(args)) {
        if ('minecraftArguments' in version) continue;
        const newValue = inherits.arguments[key];
        args[key] = newValue.concat(value);
      }
      version.libraries.push(...inherits.libraries)
    }
    return version;
  }
}

class RulesManager {
  constructor(rules, params) {
    this.rules = rules;
    this.status = true;
    this.rules.forEach((rule) => this.checkRules(rule, params));
  }

  checkFeatures(rule, params) {
    /** verify if the rules comply with the specifications */
    for (const [key, value] of Object.entries(rule.features)) {
      const invalid = !(key in params) || value !== params[key];
      const isAllow = rule.action === 'allow';
      if (invalid && isAllow) this.status = false
    }
  }

  checkSystem(rule) {
    /** Check if the system meets the */
    let status = true
    if (rule.os.name) {
      const osName = get_system_info().os_name.toLowerCase();
      const isOS = rule.os.name.toLowerCase() === osName;
      status = status && isOS;
    }
    if (rule.os.arch) {
      const osArch = get_system_info().arch;
      const isArch = osArch === rule.os.arch;
      status = status && isArch;
    }
    if (rule.os.version) {
      const osVersion = get_system_info().release;
      const reVersion = new RegExp(rule.os.version);
      const match = reVersion.test(osVersion);
      status = status && match;
    }
    // Determine the final state based on the action
    status = rule.action === 'allow' ? status : !status;
    this.status = status ? this.status : false;
  }

  checkRules(rule, params) {
    if (rule.features) this.checkFeatures(rule, params)
    if (rule.os) this.checkSystem(rule)
  }
}

class LibraryManager {
  constructor(versionName) {
    this.versionManager = new VersionManager()
    this.version = this.versionManager.getVersion(versionName)
    this.dir = get_system_info().home_path
    this.dir = path.join(this.dir, 'versions', versionName)
    let fs_rec = {recursive: true}
    this.remove_natives = (path) => fs.rmSync(path, fs_rec)
  }

  path(library) {
    /** Build the selected library path or libraries path */
    const base_path = get_system_info().home_path
    const libraries_path = path.join(base_path, 'libraries')
    if (!library) return libraries_path
    return path.join(libraries_path, library.path)
  }

  get_classifiers (library) {
    /** Get the native name using the dict */
    const os_name = get_system_info().os_name.toLowerCase();
    const os_arch = get_system_info().arch;
    if (!library.natives || !(os_name in library.natives)) return null;
    const native = library.natives[os_name];
    return native.replace('${arch}', os_arch);
  }

  get_libraries(only_natives = false) {
    /** Get the libraries list and natives */
    const libraries = []
    for (const library of this.version.libraries) {
      const downloads = library.downloads
      // If exist an artefact and not are only natives join
      // the artefact to the libraries list
      if (downloads.artifact !== undefined && !only_natives) {
        let has_rules = library.rules !== undefined
        if (has_rules) has_rules = new RulesManager(library.rules).status
        if (!library.rules || has_rules) libraries.push(downloads.artifact)
      }
      // If exists classifiers continue adding
      if (!downloads.classifiers) continue;
      const nativeName = this.get_classifiers(library)
      const natives = downloads.classifiers
      if (nativeName && nativeName in natives) libraries.push(natives[nativeName])
      if (library.extract) libraries[libraries.length - 1].extract = library.extract
    }
    return libraries;
  }

  check_libraries() {
    /** Check if any library is corrupted or not exist */
    const corrupted = [];
    for (const library of this.get_libraries()) {
      const hash = check_sha1(this.path(library))
      const isHashed = hash !== library.sha1
      if (isHashed) corrupted.push(library)
    }
    return corrupted
  }
  join_libraries (sep, base_path) {
    /** Concatenate the libraries into a string */
    let libraries = this.get_libraries()
    const get_path = (library) => (
      path.join(base_path, library.path)
    )
    libraries = libraries.map(get_path)
    return libraries.join(sep);
  }

  async download_libraries (url) {
    /** Download the corrupted or non-existent libraries */
    for (const library of this.check_libraries()) {
      const location = url ? url + library.path : library.url;
      const base_path = this.path(library);
      const base_dir = path.dirname(base_path);
      fs.mkdirSync(base_dir, { recursive: true });
      let buffer = await (await fetch(location)).arrayBuffer()
      fs.writeFileSync(base_path, Buffer.from(buffer));
    }
  }

  async download_client(url = null) {
    /** Download the jar version file using a custom url */
    const client = this.version['downloads']['client']
    if (!url) url = client['url']
    else url = url + client['sha1'] + '/client.jar'
    const version_jar = this.version['id'] + '.jar'
    const base_path = path.join(this.dir, version_jar)
    const client_exist = fs.existsSync(base_path)
    if (!client_exist && check_sha1(base_path) !== client['sha1']) {
      let buffer = await (await fetch(url)).arrayBuffer()
      fs.writeFileSync(base_path, Buffer.from(buffer))
    }
    return base_path
  }

  unzip_natives(native_dir = null) {
    /** Unzip natives and save at the specific location */
    if (!native_dir) native_dir = path.join(this.dir, 'natives')
    if (!fs.existsSync(native_dir)) {
      fs.mkdirSync(native_dir, { recursive: true })
    }
    // List only natives libraries and unzip them
    for (const native of this.get_libraries(true)) {
      const native_path = this.path(native)
      let exclude = native.extract && native.extract.exclude
      exclude = (exclude)? native['extract']['exclude'] : []
      unzip_file(native_path, native_dir, exclude)
    } return native_dir
  }
}

class ArgsManager {
  constructor(username, versionName, rules) {
    this.versionManager = new VersionManager();
    this.version = this.versionManager.getVersion(versionName);
    this.username = username;
    this.rules = rules
    this.rules.auth_player_name = username
    this.rules.version_name = versionName
  }

  joinArgs(args) {
    /**
     * Concatenate the game arguments and jvm arguments
     * using spaces
     */
    const gameArgs = [];
    for (const arg of args) {
      if (typeof arg === 'string') gameArgs.push(arg)
      else {
        const { rules, value } = arg
        let is_legacy = typeof value !== 'string'
        let processed_value = (is_legacy)? value : [value]
        const status = new RulesManager(rules, this.rules)
        if (status.status) gameArgs.push(...processed_value)
      }
    }
    return gameArgs.join(' ')
  }

  getArgs(...params) {
    /** Obtain the final arguments for the game process */
    const args = this.version['arguments'];
    let joinedParams = params.join(' ');
    let gameArgs = this.joinArgs(args['game']);
    let jvmArgs = this.joinArgs(args['jvm']);

    // Iterate over the rules and replace the corresponding
    // values in the arguments.
    for (const [key, value] of Object.entries(this.rules)) {
      const searchValue = '${' + key + '}';
      const replaceValue = String(value);
      jvmArgs = jvmArgs.replace(searchValue, replaceValue);
      joinedParams = joinedParams.replace(searchValue, replaceValue);
      gameArgs = gameArgs.replace(searchValue, replaceValue);
    }

    const processedArgs = [jvmArgs, joinedParams, gameArgs];
    return processedArgs.filter(arg => arg !== '').join(' ');
  }
}

export {get_system_info, set_option, get_settings, unzip_file, VersionManager, RulesManager, LibraryManager, ArgsManager, VERSIONS}