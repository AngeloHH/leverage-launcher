import { get_settings, get_system_info, TEXTURES } from "./config";
import crypto from "crypto"
import path from "path"
import fs from "fs"

class OfflinePlayer {
  constructor(username) {
    /** The OfflinePlayer class represents an offline
     * player in Minecraft */
    this.username = username
    this.user_type = 'mojang'
    this.uuid = this.get_uuid()
    this.expires_on = new Date().toJSON()

    // Method to set the user type
    this.set_type = (user_type) =>
      (this.user_type = user_type)

    // Method to convert the object to JSON
    this.toJSON = () => ({
      name: this.username,
      uuid: this.uuid,
      expiresOn: this.expires_on
    })
  }

  get_uuid() {
    /** Method to generate the account UUID in offline mode */
    let hash = crypto.createHash('md5')
    let value = /^(.{8})(.{4})(.{4})(.{4})(.{12})$/

    // Generate the hash based on the username
    hash.update(`OfflinePlayer:${this.username}`)
    hash = hash.digest('hex').toString()

    // Format the hash into UUID format
    return hash.replace(value, "$1-$2-$3-$4-$5")
  }
}

function accounts() {
  /**
   * The accounts function retrieves the
   * contents of the usercache.json file,
   * which contains information about user
   * accounts. */
  let dir = get_system_info()['home_path']
  let p = path.join(dir, 'usercache.json')
  return JSON.parse(fs.readFileSync(p, 'UTF-8'))
}

function get_skin(texture) {
  /**
   * Takes a texture object as input and retrieves the skin
   * and cape textures from it
   */
  // Extract the value field from the texture object and decode
  // it from base64
  let value = Buffer.from(texture['value'], 'base64')
  // Parse the decoded value as a JSON and extract the textures
  value = JSON.parse(value.toString())['textures']
  return { skin: value['SKIN'], cape: value['CAPE'] }
}

async function get_textures(uuid) {
  // Remove dashes from the UUID
  uuid = uuid.replace(/-/g, '')

  // Get the textures URL from the settings
  let textures = get_settings()['textures']

  // Construct the URL for retrieving the
  // player's textures
  let url = TEXTURES + uuid
  url = (textures)? textures + uuid : url

  // Fetch the data from the URL
  let data = await fetch(url)
  if (data.status !== 200) return undefined
  data = await data.json()

  // Extract the array from the data and map each texture to
  // the skin object
  return data['properties'].map(texture => get_skin(texture))
}

function get_genre(uuid) {
  /** calculate the genre based on the UUID provided */
  let x = Array(3).fill(null)
  uuid = uuid.replace(/-/g, '')

  // Iterate over each element in the x array
  let diff = parseInt(uuid[7], 16)
  x.forEach((x, index) => {
    // Perform XOR operation between the diff and the
    // hexadecimal value at the corresponding position
    // in the UUID
    diff = diff ^ parseInt(uuid[8 * index])
  })
  return x ? "alex" : "steve"
}

export { OfflinePlayer, accounts, get_textures, get_genre }