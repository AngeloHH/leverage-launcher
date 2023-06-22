import * as path from "path"
import express from "express"
import { get_settings, get_system_info, set_option, VersionManager, VERSIONS } from "./game/config"
import * as fs from "fs"
import { accounts, get_genre, get_textures, OfflinePlayer } from "./game/auth"
import Profile from "./game/profile"
import runGame from "./game/launch"
import { check_sha1, recursive_files } from "./game/files"
import { Notification } from 'electron'
import ps from 'ps-node'
import { exec } from 'child_process'

const app = express(), version_manager = new VersionManager()
const router = express.Router()
app.set('views', path.join(__dirname, '..', 'public'))
app.use(express.json())
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Methods', '*')
  res.setHeader('Access-Control-Allow-Headers', '*')
  next()
})
app.use(router); app.use(express.static(app.get('views')))

function check_params(keys, params) {
  let error = {detail: []}
  let missing = !keys.every(key => {
    let has_key = params.hasOwnProperty(key)
    let msg = 'field required'
    let value = {loc: ['query', key], msg: msg}
    if (!has_key) error.detail.push(value)
    return has_key
  })
  return (missing)? error : undefined
}

function settings(req, res) {
  res.setHeader('Content-Type', 'application/json')
  if (req.method.toLowerCase() === 'post') {
    Object.keys(req.body).forEach(key => {
      set_option(key, req.body[key])
    })
  }
  res.end(JSON.stringify(get_settings()))
}

app.get('/', (req, res) => {
  res.setHeader('Content-Type', 'text/html')
  res.sendFile(path.join(app.get('views'), 'index.html'))
})

router.get('/versions/', async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let versions = version_manager.localVersions()
  let offline = req.query.offline === '' || JSON.parse(req.query.offline)
  if (!offline) versions = await (await fetch(VERSIONS)).json()
  res.end(JSON.stringify(versions))
})

router.get("/versions/:version_name.json", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let version = version_manager.getVersion(req.params.version_name)
  res.end(JSON.stringify(version))
})

router.get("/launcher/profiles/", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let dir = get_system_info()['home_path']
  let file_name = 'launcher_profiles.json'
  res.end(fs.readFileSync(path.join(dir, file_name)))
})

router.get("/launcher/options/", settings).post("/launcher/options/", settings)

router.post("/launcher/profiles/", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let keys = ['name', 'version', 'args', 'java_dir']
  let error = check_params(keys, req.body)
  if (error) return res.end(JSON.stringify(error))
  let profile = new Profile(...keys.map(key => req.body[key]))
  profile.save()
  res.end(JSON.stringify({'saved': profile.profileId}))
})

router.delete("/launcher/profiles/", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let dir = get_system_info()['home_path']
  let file_name = 'launcher_profiles.json'
  dir = path.join(dir, file_name)
  let data = JSON.parse(fs.readFileSync(dir, 'utf-8'))
  delete data['profiles'][req.query.id]
  data = JSON.stringify(data, null, 2)
  fs.writeFileSync(dir, data)
  res.end(JSON.stringify({'deleted': req.query.id}))
})

router.get("/account/", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  res.end(JSON.stringify(accounts()))
})

router.get("/account/skin/:uuid", async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let textures
  try {textures = await get_textures(req.params.uuid)}
  catch (error) {textures = undefined}
  let genre = {'default': get_genre(req.params.uuid)}
  res.end(JSON.stringify((textures)? textures : genre))
})

router.post("/account/login/", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let file = path.join(get_system_info()['home_path'], 'usercache.json')
  let account = new OfflinePlayer(req.body.username)
  let profiles = accounts(); account = account.toJSON()
  Object.keys(req.body).forEach(key => account[key] = account[key])
  let exist = profiles.some(profile => {
    return profile.uuid === account.uuid
  })
  if (!exist) profiles.push(account)
  let data = JSON.stringify(profiles, null, 2)
  fs.writeFileSync(file, data); res.end(JSON.stringify(account))
})

router.post("/game/play/", async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let account = req.body.account, type = account.type
  let profile = new Profile('')
  profile.get_profile(req.body.profile_id)
  account = new OfflinePlayer(account.name)

  if (account.type) account.set_type(type)
  Object.keys(req.body.account).forEach(key =>
    (account[key] = req.body.account[key]))

  let game = await runGame(profile.lastVersionId, account, profile.javaDir)
  res.end(JSON.stringify({'process': game.pid}))
  game.stdout.on('data', (data) => console.log(`${data}`))
  game.stderr.on('data', (data) => console.error(`${data}`))

  game.on('close', (code) => {
    let exit_text = 'Child process terminated with exit'
    console.log(exit_text + ' code: ' + code.toString())
    }
  )
})

router.get("/game/files/", async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let dir = get_system_info()['home_path']
  dir = path.join(dir, req.query.dirname)
  let files = recursive_files(dir).map(file => {
    let file_path = path.join(dir, file)
    let hash = check_sha1(file_path)
    let file_name = file_path.split(path.sep)
    file_name = file_name[file_name.length - 1]
    return {filename: file_name, filehash: hash}
  })
  res.end(JSON.stringify(files))
})

router.get("/game/notification/", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  const notification = new Notification({
    title: req.query.title, body: req.query.text,
  })
  notification.show(); res.end(JSON.stringify(req.query))
})

router.get("/game/list/", async (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  await ps.lookup({},(error, process) => {
    if (error) return res.end(JSON.stringify({error: error}))
    let data = []
    process.forEach(p => {
      let is_minecraft = p.arguments.indexOf('--gameDir')
      let is_java = p.command.includes('java')
      if (is_minecraft !== -1 && is_java) data.push(p)
    })
    res.end(JSON.stringify({process: data}))
  })
})

router.delete("/game/list/", (req, res) => {
  res.setHeader('Content-Type', 'application/json')
  let win_kill = `taskkill /PID ${req.query.pid} /T /F`
  let linux_kill = 'kill -9 ' + req.query.pid
  let command = process.platform === 'win32' ? win_kill : linux_kill
  exec(command, (stdout, stderr) =>
    res.end(JSON.stringify({status: stdout || stderr})))
})

export { app as Web }