package lighthouse.subwindows;

import com.google.common.base.*;
import com.google.common.net.*;
import com.vinumeris.crashfx.*;
import javafx.application.Platform;
import javafx.collections.*;
import javafx.event.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.stage.*;
import lighthouse.*;
import lighthouse.model.*;
import lighthouse.protocol.*;
import lighthouse.utils.*;
import org.slf4j.*;

import java.io.*;
import java.nio.file.*;

import static lighthouse.utils.GuiUtils.*;

/**
 * Screen where user chooses between server assisted and serverless mode.
 */
public class AddProjectTypeWindow {
    private static final Logger log = LoggerFactory.getLogger(AddProjectTypeWindow.class);

    @FXML RadioButton fullyDecentralised;
    @FXML RadioButton serverAssisted;
    @FXML ComboBox<String> serverNameCombo;
    @FXML Button saveButton;

    private ProjectModel model;
    private boolean editing;

    public Main.OverlayUI<InnerWindow> overlayUI;

    public static Main.OverlayUI<AddProjectTypeWindow> open(ProjectModel projectModel, boolean editing) {
        Main.OverlayUI<AddProjectTypeWindow> result = Main.instance.overlayUI("subwindows/add_project_type.fxml",
                editing ? "Change type" : "Select type");
        result.controller.setModel(projectModel);
        result.controller.editing = editing;
        return result;
    }

    private void setModel(ProjectModel model) {
        this.model = model;
        if (model.serverName.get() != null) {
            serverNameCombo.setValue(model.serverName.get());
            serverAssisted.setSelected(true);
        } else {
            fullyDecentralised.setSelected(true);
        }
    }

    public void initialize() {
        ObservableList<String> hostnames = FXCollections.observableArrayList(ServerList.hostnameToServer.keySet());
        serverNameCombo.itemsProperty().set(hostnames);
        serverNameCombo.disableProperty().bind(fullyDecentralised.selectedProperty());
    }

    private boolean isServerNameValid(String str) {
        try {
            if (str.equals("localhost")) return true;
            HostAndPort hostAndPort = HostAndPort.fromString(str);
            return (InternetDomainName.isValid(hostAndPort.getHostText()) &&
                    InternetDomainName.from(hostAndPort.getHostText()).isUnderPublicSuffix());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Returns true if form is valid.
    private boolean validateAndSync() {
        if (serverAssisted.isSelected()) {
            if (serverNameCombo.getValue() == null || serverNameCombo.getValue().equals("")) {
                GuiUtils.arrowBubbleToNode(serverNameCombo, "You must pick a server.");
                return false;
            } else if (!isServerNameValid(serverNameCombo.getValue())) {
                GuiUtils.arrowBubbleToNode(serverNameCombo, "The server name is not considered valid.");
                return false;
            }
        }

        model.serverName.set(serverAssisted.isSelected() ? serverNameCombo.getValue() : "");
        return true;
    }

    @FXML
    public void saveClicked(ActionEvent event) {
        // Work around ConcurrentModificationException error.
        Platform.runLater(() -> {
            if (!validateAndSync())
                return;

            final LHProtos.ProjectDetails detailsProto = model.getDetailsProto().build();
            log.info("Saving: {}", detailsProto.getExtraDetails().getTitle());
            try {
                Project project;
                if (detailsProto.hasPaymentUrl()) {
                    // User has to explicitly export it somewhere (not watched) so they can get it to the server.
                    project = Main.backend.saveProject(model.getProject());
                    ExportWindow.openForProject(project);
                } else {
                    GuiUtils.informationalAlert("Folder watching",
                            "The folder to which you save your project file will be watched for pledge files. When you receive them from backers, just put them in the same directory and they will appear.");
                    // Request directory first then save, so the animations are right.
                    DirectoryChooser chooser = new DirectoryChooser();
                    chooser.setTitle("Select a directory to store the project and pledges");
                    platformFiddleChooser(chooser);
                    File dir = chooser.showDialog(Main.instance.mainStage);
                    if (dir == null)
                        return;
                    final Path dirPath = dir.toPath();
                    project = model.getProject();
                    // Make sure we don't try and run too many animations simultaneously.
                    final Project fp = project;
                    overlayUI.runAfterFade(ev -> {
                        saveAndWatchDirectory(fp, dirPath);
                    });
                    overlayUI.done();
                }
            } catch (IOException e) {
                log.error("Could not save project", e);
                informationalAlert("Could not save project",
                        "An error was encountered whilst trying to save the project: %s",
                        Throwables.getRootCause(e));
            }
        });
    }

    private void saveAndWatchDirectory(Project project, Path dirPath) {
        try {
            Path file = dirPath.resolve(project.getSuggestedFileName());
            try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(file))) {
                project.getProto().writeTo(stream);
            }
            Main.backend.importProjectFrom(file);
        } catch (IOException e) {
            CrashFX.propagate(e);
        }
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        if (editing)
            EditProjectWindow.openForEdit(model);
        else
            EditProjectWindow.openForCreate(model);
    }
}
