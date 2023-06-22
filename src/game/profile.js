import { get_system_info } from "./config"
import fs from "fs"
import path from "path"
import crypto from "crypto"

class Profile {
  constructor(name, version, args, java_path) {
    this.created = new Date().toJSON()
    this.icon = "Dirt"; this.name = name
    this.lastVersionId = version
    this.lastUsed = this.created
    this.javaArgs = args; this.javaDir = java_path
    this.type = 'custom'
    // Generate a unique profile ID based
    // on the profile name
    this.profileId = this.get_sha1(name)
  }

  get_profile(profile_id) {
    /** Retrieve a profile by profile ID from the launcher_profiles.json */
    let dir = get_system_info()['home_path']
    let file_name = 'launcher_profiles.json'
    let profile = fs.readFileSync(path.join(dir, file_name), 'utf-8')
    profile = JSON.parse(profile)['profiles'][profile_id]

    // Assign the profile properties to the current instance
    Object.keys(profile).forEach(key => (this[key] = profile[key]))
    return this
  }

  get_sha1(version) {
    /** Generate a SHA-1 hash of the given version. */
    let sha1 = crypto.createHash('sha1')
    return sha1.update(version).digest('hex')
  }

  save() {
    /** Save the profile to the launcher_profiles.json */
    let dir = get_system_info()['home_path']
    let file_name = 'launcher_profiles.json'
    let file = path.join(dir, file_name)

    // Read the launcher_profiles.json file and parse its contents.
    let data = JSON.parse(fs.readFileSync(file, 'utf-8'))
    let args = {}
    Object.keys(this).forEach(key => {
      if ('profileId' !== key) args[key] = this[key]
    })

    // Write the updated data back to the launcher_profiles.json file
    data['profiles'][this.profileId] = args
    fs.writeFileSync(file, JSON.stringify(data, null, 2))
  }
}

export default Profile