import os
import sys
from pathlib import Path

from PySide6.QtCore import Qt, QStandardPaths, QUrl, Signal
from PySide6.QtGui import QCloseEvent
from PySide6.QtWidgets import QApplication, QMainWindow, QMessageBox
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView

APP_NAME = "Backtest Lab"
APP_DIR_NAME = "BacktestLab"
HTML_NAME = "TradingBoard_v12.html"


def app_data_dir() -> Path:
    base = os.environ.get("APPDATA") or os.path.expanduser("~")
    path = Path(base) / APP_DIR_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def resource_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys._MEIPASS) / "tradingboard-windows"
    return Path(__file__).resolve().parent


class DesktopPage(QWebEnginePage):
    js_error = Signal(str)

    def javaScriptConsoleMessage(self, level, message, line_number, source_id):
        if level == QWebEnginePage.JavaScriptConsoleMessageLevel.ErrorMessageLevel:
            self.js_error.emit(f"{message} (line {line_number})")
        super().javaScriptConsoleMessage(level, message, line_number, source_id)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle(APP_NAME)
        self.resize(1440, 900)
        self.setMinimumSize(1100, 720)

        profile_dir = app_data_dir() / "web-profile"
        cache_dir = app_data_dir() / "web-cache"
        profile_dir.mkdir(parents=True, exist_ok=True)
        cache_dir.mkdir(parents=True, exist_ok=True)

        self.profile = QWebEngineProfile("BacktestLab", self)
        self.profile.setPersistentStoragePath(str(profile_dir))
        self.profile.setCachePath(str(cache_dir))
        self.profile.setPersistentCookiesPolicy(QWebEngineProfile.ForcePersistentCookies)
        self.profile.setHttpCacheType(QWebEngineProfile.DiskHttpCache)
        self.profile.downloadRequested.connect(self.handle_download)

        self.page = DesktopPage(self.profile, self)
        self.page.js_error.connect(self.show_js_error)
        self.view = QWebEngineView(self)
        self.view.setPage(self.page)
        self.view.setContextMenuPolicy(Qt.NoContextMenu)
        settings = self.view.settings()
        settings.setAttribute(QWebEngineSettings.JavascriptEnabled, True)
        settings.setAttribute(QWebEngineSettings.LocalStorageEnabled, True)
        settings.setAttribute(QWebEngineSettings.PluginsEnabled, True)
        settings.setAttribute(QWebEngineSettings.FullScreenSupportEnabled, True)
        settings.setAttribute(QWebEngineSettings.LocalContentCanAccessFileUrls, True)
        settings.setAttribute(QWebEngineSettings.LocalContentCanAccessRemoteUrls, False)
        settings.setAttribute(QWebEngineSettings.AllowRunningInsecureContent, False)
        settings.setAttribute(QWebEngineSettings.ErrorPageEnabled, True)
        self.setCentralWidget(self.view)

        self._connect_optional_permissions()
        self._load_source()

    def _connect_optional_permissions(self):
        signal = getattr(self.page, "permissionRequested", None)
        if signal is not None:
            signal.connect(self._handle_permission)

    def _handle_permission(self, permission):
        try:
            ptype = str(permission.permissionType())
            if any(k in ptype for k in ("MediaAudioCapture", "MediaVideoCapture", "MediaAudioVideoCapture")):
                permission.grant()
        except Exception:
            pass

    def _load_source(self):
        html_path = resource_dir() / HTML_NAME
        if not html_path.exists():
            QMessageBox.critical(self, "Backtest Lab", f"فایل رابط برنامه پیدا نشد:\n{html_path}")
            return
        self.view.load(QUrl.fromLocalFile(str(html_path.resolve())))

    def handle_download(self, item):
        try:
            base = Path(QStandardPaths.writableLocation(QStandardPaths.DownloadLocation) or (Path.home() / "Downloads"))
            base.mkdir(parents=True, exist_ok=True)
            name = item.downloadFileName() or "BacktestLab-download"
            target = base / name
            stem, suffix = target.stem, target.suffix
            counter = 2
            while target.exists():
                target = base / f"{stem} ({counter}){suffix}"
                counter += 1
            item.setDownloadDirectory(str(target.parent))
            item.setDownloadFileName(target.name)
            item.accept()
        except Exception as exc:
            QMessageBox.warning(self, "Backtest Lab", f"ذخیره فایل انجام نشد:\n{exc}")

    def show_js_error(self, message):
        print(f"[TradingBoard JS Error] {message}", file=sys.stderr)

    def closeEvent(self, event: QCloseEvent):
        event.accept()


def main() -> int:
    QApplication.setApplicationName(APP_NAME)
    QApplication.setOrganizationName("BacktestLab")
    QApplication.setApplicationDisplayName(APP_NAME)
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
