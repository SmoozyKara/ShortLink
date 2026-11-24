package shortener.infra;

import shortener.core.Browser;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

public final class DesktopBrowser implements Browser {
    @Override
    public void open(URI url) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Открытие браузера недоступно в этой среде. Запустите приложение на рабочем столе.");
        }
        Desktop.getDesktop().browse(url);
    }
}
