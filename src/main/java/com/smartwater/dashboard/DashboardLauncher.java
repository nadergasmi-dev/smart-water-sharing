package com.smartwater.dashboard;

/**
 * Point d'entrée du dashboard depuis l'IDE ou {@code java -cp} : une classe principale qui n'hérite pas de
 * {@code Application} évite l'erreur « JavaFX runtime components are missing » quand JavaFX est sur le
 * classpath (et non sur le module path). Avec Maven : {@code mvn javafx:run}.
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public final class DashboardLauncher {

    private DashboardLauncher() { }

    public static void main(String[] args) {
        DashboardApp.main(args);
    }
}
