import fs from "fs"
import crypto from "crypto"
import path from "path";

function check_sha1(filePath) {
  /** calculate the SHA-1 hash of a file located
   * at the specified filePath. */
  // If the file doesn't exist, it returns null.
  if (!fs.existsSync(filePath)) return null
  const data = fs.readFileSync(filePath)
  const sha1 = crypto.createHash('sha1')
  return sha1.update(data).digest('hex')
}

function recursive_files(dir) {
  /** Provide a way to retrieve a list of files in a
   * directory and its subdirectories recursively. */
  // Read the files in the specified directory
  let files = fs.readdirSync(dir)
  // Iterate through each file in the directory
  files.forEach((file, index) => {
    let file_path = path.join(dir, file)
    let stats = fs.statSync(file_path)
    // Check if the file is a directory
    if (stats.isDirectory()) {
      // Recursively retrieve the files in the subdirectory
      let r_files = recursive_files(file_path)
      r_files.forEach(f => files.push(path.join(file, f)))
      // Remove the subdirectory entry from the current files.
      files.splice(index, 1)
    }
  })
  return files
}

export { check_sha1, recursive_files }