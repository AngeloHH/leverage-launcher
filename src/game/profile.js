import { get_system_info } from "./config";
import fs from "fs";
import path from "path";
import crypto from "crypto";

class Profile {
  constructor(name, version, args, java_path) {
    this.created = new Date().toJSON()
    this.icon = "Dirt"; this.name = name
    this.lastVersionId = version
    this.lastUsed = this.created
    this.javaArgs = args; this.javaDir = java_path
    this.type = 'custom'
    this.profileId = this.get_sha1(name)
  }

  get_profile(profile_id) {
    let dir = get_system_info()['home_path']
    let file_name = 'launcher_profiles.json'
    let profile = fs.readFileSync(path.join(dir, file_name), 'utf-8')
    profile = JSON.parse(profile)['profiles'][profile_id]
    Object.keys(profile).forEach(key => (this[key] = profile[key]))
    return this
  }

  get_sha1(version) {
    let sha1 = crypto.createHash('sha1')
    return sha1.update(version).digest('hex')
  }

  save() {
    let dir = get_system_info()['home_path']
    let file_name = 'launcher_profiles.json'
    let file = path.join(dir, file_name)
    let data = JSON.parse(fs.readFileSync(file, 'utf-8'))
    let args = {}
    Object.keys(this).forEach(key => {
      if ('profileId' !== key) args[key] = this[key]
    })
    data['profiles'][this.profileId] = args
    fs.writeFileSync(file, JSON.stringify(data, null, 2))
  }
}

export default Profile