// Modules to control application life and create native browser window
import { app, BrowserWindow } from 'electron'
import { Web } from "./core"
import path from "path"

// Set the port for the web server to the value specified in the
// environment variable, PORT or use port 3000 as the default if
// the environment variable is not set.
Web.set('port', process.env.PORT || 3000)
// Start listening on the port specified in the configuration.
Web.listen(Web.get('port'))

const createWindow = () => {
  // Create the browser window.
  const mainWindow = new BrowserWindow({
    width: 600, height: 900, maxWidth: 600, maxHeight: 900,
    minWidth: 600, minHeight: 900,
    icon: path.join(__dirname, '..', 'public', 'favicon.ico')
  })

  // and load the index.html of the app.
  // mainWindow.loadFile('index.html')
  mainWindow.loadURL(`http://localhost:${Web.get('port')}/`)

  // Open the DevTools.
  // mainWindow.webContents.openDevTools()

  // Hide the menu-bar
  mainWindow.menuBarVisible = false
}

// This method will be called when Electron has finished
// initialization and is ready to create browser windows.
// Some APIs can only be used after this event occurs.
app.whenReady().then(() => {
  createWindow()

  app.on('activate', () => {
    // On macOS it's common to re-create a window in the app when the
    // dock icon is clicked and there are no other windows open.
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

// Quit when all windows are closed, except on macOS. There, it's
// common for applications and their menu bar to stay active until
// the user quits explicitly with Cmd + Q.
app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

// In this file you can include the rest of your app's specific
// main process code. You can also put them in separate files and
// require them here.
