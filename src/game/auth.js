import { get_system_info } from "./config";

const uuid = require('uuid')
import crypto from "crypto";
import path from "path";
import fs from "fs";
class OfflinePlayer {
  constructor(username) {
    this.username = username
    this.user_type = 'mojang'
    this.uuid = this.get_uuid()
    this.expires_on = new Date().toJSON()
    this.set_type = (user_type) => (this.user_type = user_type)
    this.toJSON = () => ({
      name: this.username,
      uuid: this.uuid,
      expiresOn: this.expires_on
    })
  }

  get_uuid() {
    let hash = crypto.createHash('md5')
    let value = /^(.{8})(.{4})(.{4})(.{4})(.{12})$/
    hash.update(`OfflinePlayer:${this.username}`)
    hash = hash.digest('hex').toString()
    return hash.replace(value, "$1-$2-$3-$4-$5")
  }
}

function accounts() {
  let dir = get_system_info()['home_path']
  let p = path.join(dir, 'usercache.json')
  return JSON.parse(fs.readFileSync(p, 'UTF-8'))
}

function get_skin(texture) {
  let value = Buffer.from(texture['value'], 'base64')
  value = JSON.parse(value.toString())['textures']
  return { skin: value['SKIN'], cape: value['CAPE'] }
}

async function get_textures(uuid) {
  uuid = uuid.replace(/-/g, '')
  let url = `https://sessionserver.mojang.com/session/minecraft/profile/${uuid}`
  let data = await fetch(url)
  if (data.status !== 200) return undefined
  data = await data.json()
  return data['properties'].map(texture => get_skin(texture))
}

function get_genre(uuid) {
  let x = Array(3).fill(null)
  uuid = uuid.replace(/-/g, '')
  let diff = parseInt(uuid[7], 16)
  x.forEach((x, index) => {
    diff = diff ^ parseInt(uuid[8 * index])
  })
  return x ? "alex" : "steve"
}

export { OfflinePlayer, accounts, get_textures, get_genre }