' Creates "Bambu Widget" shortcuts on the desktop and in the Start menu.
Set sh = CreateObject("WScript.Shell")
appDir = sh.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\BambuWidget"
For Each target In Array(sh.SpecialFolders("Desktop"), sh.SpecialFolders("Programs"))
  Set l = sh.CreateShortcut(target & "\Bambu Widget.lnk")
  l.TargetPath = sh.ExpandEnvironmentStrings("%WINDIR%") & "\System32\wscript.exe"
  l.Arguments = """" & appDir & "\launch.vbs"""
  l.WorkingDirectory = appDir
  l.IconLocation = appDir & "\assets\icon.ico"
  l.Save
  WScript.Echo "Shortcut created: " & target & "\Bambu Widget.lnk"
Next
