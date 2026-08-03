package ru.amereco.amerecolauncher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SettingsControllerTest {

    @Nested
    @DisplayName("защита полной очистки каталога")
    class RefuseToWipe {
        @Test
        @DisplayName("обычный каталог лаунчера стирать можно")
        void ordinaryDirectoryAllowed() {
            assertNull(SettingsController.refuseToWipe(
                    Path.of(System.getProperty("user.home"), ".local/share/amerecolauncher/.minceraft")));
            assertNull(SettingsController.refuseToWipe(Path.of("/srv/games/minecraft")));
        }

        @Test
        @DisplayName("корень файловой системы — отказ")
        void filesystemRootRefused() {
            assertNotNull(SettingsController.refuseToWipe(Path.of("/")));
        }

        @Test
        @DisplayName("домашний каталог — отказ")
        void homeRefused() {
            assertNotNull(SettingsController.refuseToWipe(
                    Path.of(System.getProperty("user.home"))));
        }

        @Test
        @DisplayName("личные каталоги — отказ")
        void personalDirectoriesRefused() {
            Path home = Path.of(System.getProperty("user.home"));
            assertNotNull(SettingsController.refuseToWipe(home.resolve("Desktop")));
            assertNotNull(SettingsController.refuseToWipe(home.resolve("Documents")));
            assertNotNull(SettingsController.refuseToWipe(home.resolve("Загрузки")));
        }

        @Test
        @DisplayName("ненормализованный путь до домашнего каталога тоже отсекается")
        void normalisationIsApplied() {
            Path tricky = Path.of(System.getProperty("user.home"), "игра", "..")
                    .toAbsolutePath().normalize();
            assertNotNull(SettingsController.refuseToWipe(tricky));
        }
    }

    @Nested
    @DisplayName("FXML и контроллеры не разъехались")
    class Handlers {
        /**
         * Ссылка {@code onAction="#имя"} проверяется только при клике, поэтому опечатка
         * доживает до пользователя. Здесь она падает при сборке.
         */
        @Test
        void settingsFxmlHandlersExist() {
            assertHandlersExist("/ru/amereco/amerecolauncher/settings.fxml", SettingsController.class);
        }

        @Test
        void mainFxmlHandlersExist() {
            assertHandlersExist("/ru/amereco/amerecolauncher/main.fxml", MainController.class);
        }

        @Test
        @DisplayName("обе кнопки очистки на месте")
        void bothWipeButtonsPresent() {
            String fxml = read("/ru/amereco/amerecolauncher/settings.fxml");
            assertTrue(fxml.contains("#resetResources"), "нет кнопки перезагрузки ресурсов");
            assertTrue(fxml.contains("#wipeGameDirectory"), "нет кнопки полной очистки");
            assertTrue(fxml.indexOf("#resetResources") < fxml.indexOf("#wipeGameDirectory"),
                    "кнопка полной очистки должна идти ниже");
        }

        private void assertHandlersExist(String resource, Class<?> controller) {
            Set<String> handlers = new LinkedHashSet<>();
            Matcher matcher = Pattern.compile("on[A-Za-z]+\\s*=\\s*\"#([A-Za-z_][A-Za-z0-9_]*)\"")
                    .matcher(read(resource));
            while (matcher.find()) {
                handlers.add(matcher.group(1));
            }
            assertTrue(handlers.size() > 0, "в " + resource + " не нашлось ни одного обработчика");

            for (String handler : handlers) {
                assertTrue(hasMethod(controller, handler),
                        () -> resource + " ссылается на #" + handler
                                + ", которого нет в " + controller.getSimpleName());
            }
        }

        private boolean hasMethod(Class<?> type, String name) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() <= 1) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String read(String resource) {
            try (InputStream in = SettingsControllerTest.class.getResourceAsStream(resource)) {
                assertNotNull(in, "нет ресурса " + resource);
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Nested
    @DisplayName("профили")
    class ProfileLayout {
        @Test
        @DisplayName("ключ фичи выводится из имени профиля")
        void featureKeys() {
            assertEquals("profile_rpcraft", Profiles.featureKey("rpcraft"));
            assertEquals("profile_rpcraft_admin", Profiles.featureKey("rpcraft-admin"));
            assertEquals("profile_rpcraft_ultra", Profiles.featureKey("rpcraft-ultra"));
        }

        @Test
        @DisplayName("базовый профиль идёт первым, оверлеи — в порядке наложения")
        void order() {
            assertEquals(java.util.List.of("rpcraft", "rpcraft-admin", "rpcraft-ultra"),
                    Profiles.all());
        }

        @Test
        @DisplayName("настройки чекбоксов совпадают с ключами профилей")
        void settingsFxmlUsesSameKeys() {
            // Чекбоксы в settings.fxml названы по профилям; если список профилей
            // разойдётся с экраном настроек, отключить оверлей станет нечем.
            String fxml = new Handlers().read("/ru/amereco/amerecolauncher/settings.fxml");
            assertTrue(fxml.contains("rpcraftAdminCheckbox"));
            assertTrue(fxml.contains("rpcraftUltraCheckbox"));
            assertEquals(2, Profiles.overlays().size());
        }
    }
}
