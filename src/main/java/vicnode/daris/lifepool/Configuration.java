package vicnode.daris.lifepool;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

public class Configuration {

    public static final String CONFIG_FILE_PATH = System
            .getProperty("user.home") + File.separator + ".femur_upload.conf";
    private static boolean _loaded = false;

    public static void load() throws Throwable {
        if (!_loaded) {
            Properties props = new Properties();
            InputStream in = new BufferedInputStream(
                    new FileInputStream(CONFIG_FILE_PATH));
            try {
                props.load(in);
                _loaded = true;
                _masterSpreadsheetPath = props
                        .getProperty("master.spreadsheet.path")
                        .replaceAll("^\"|\"$", "");
                _root = props.getProperty("root").replaceAll("^\"|\"$", "");
                _host = props.getProperty("host").replaceAll("^\"|\"$", "");
                _port = Integer.parseInt(props.getProperty("port"));
                _ssl = Boolean.parseBoolean(props.getProperty("ssl"));
                _domain = props.getProperty("domain").replaceAll("^\"|\"$", "");
                _user = props.getProperty("user").replaceAll("^\"|\"$", "");
                _password = props.getProperty("password").replaceAll("^\"|\"$",
                        "");
                _projectName = props.getProperty("project.name")
                        .replaceAll("^\"|\"$", "");
                _projectDesc = props.getProperty("project.description")
                        .replaceAll("^\"|\"$", "");
                _swiftContainer = props.getProperty("swift.container")
                        .replaceAll("^\"|\"$", "");
                _tiles100umSony = props.getProperty("tiles.100um.sony")
                        .replaceAll("^\"|\"$", "");
                _tiles100umSpot = props.getProperty("tiles.100um.spot")
                        .replaceAll("^\"|\"$", "");
            } finally {
                in.close();
            }
        }
    }

    private static String _masterSpreadsheetPath;
    private static String _root;
    private static String _host;
    private static int _port;
    private static boolean _ssl;
    private static String _domain;
    private static String _user;
    private static String _password;
    private static String _projectName;
    private static String _projectDesc;
    private static String _swiftContainer;
    private static String _tiles100umSony;
    private static String _tiles100umSpot;

    public static String masterSpreadsheetPath() throws Throwable {
        load();
        return _masterSpreadsheetPath;
    }

    public static String root() throws Throwable {
        load();
        return _root;
    }

    public static String host() throws Throwable {
        load();
        return _host;
    }

    public static int port() throws Throwable {
        load();
        return _port;
    }

    public static boolean ssl() throws Throwable {
        load();
        return _ssl;
    }

    public static String domain() throws Throwable {
        load();
        return _domain;
    }

    public static String user() throws Throwable {
        load();
        return _user;
    }

    public static String password() throws Throwable {
        load();
        return _password;
    }

    public static String projectName() throws Throwable {
        load();
        return _projectName;
    }

    public static String projectDescription() throws Throwable {
        load();
        return _projectDesc;
    }

    public static String swiftContainer() throws Throwable {
        load();
        return _swiftContainer;
    }

    public static String tiles100umSony() throws Throwable {
        load();
        return _tiles100umSony;
    }

    public static String tiles100umSpot() throws Throwable {
        load();
        return _tiles100umSpot;
    }

    public static void main(String[] args) throws Throwable {
        System.out.println("root: " + root());
        System.out.println("host: " + host());
        System.out.println("port: " + port());
        System.out.println("ssl: " + ssl());
        System.out.println("domain: " + domain());
        System.out.println("user: " + user());
        System.out.println("password: " + password());
        System.out.println("project.name: " + projectName());
        System.out.println("project.description: " + projectDescription());
        System.out.println("swift.container: " + swiftContainer());
        System.out
                .println("master.spreadsheet.path: " + masterSpreadsheetPath());
        System.out.println("tiles.100um.sony: " + tiles100umSony());
        System.out.println("tiles.100um.spot: " + tiles100umSpot());
    }
}
