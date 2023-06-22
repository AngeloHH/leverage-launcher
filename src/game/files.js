import fs from "fs"
import crypto from "crypto"
import path from "path";

function check_sha1(filePath) {
  if (!fs.existsSync(filePath)) return null
  const data = fs.readFileSync(filePath)
  const sha1 = crypto.createHash('sha1')
  return sha1.update(data).digest('hex')
}

function recursive_files(dir) {
  let files = fs.readdirSync(dir)
  files.forEach((file, index) => {
    let file_path = path.join(dir, file)
    let stats = fs.statSync(file_path)
    if (stats.isDirectory()) {
      let r_files = recursive_files(file_path)
      r_files.forEach(f => files.push(path.join(file, f)))
      files.splice(index, 1)
    }
  })
  return files
}

export { check_sha1, recursive_files }