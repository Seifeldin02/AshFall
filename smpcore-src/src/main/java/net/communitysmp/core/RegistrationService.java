package net.communitysmp.core;

import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;

/**
 * Compact Floodgate registration surface. AuthMe still performs the actual
 * registration, password validation, hashing, timeout, and authenticated login.
 */
final class RegistrationService {
    private final SMPCore plugin;
    private final java.util.Map<java.util.UUID,Integer> formAttempts=new java.util.HashMap<>();

    RegistrationService(SMPCore plugin){this.plugin=plugin;}

    void openBedrock(Player player){
        if(!player.isOnline()||!plugin.isBedrock(player))return;
        try{
            if(AuthMeApi.getInstance().isRegistered(player.getName()))return;
            GeyserConnection connection=GeyserApi.api().connectionByUuid(player.getUniqueId());
            if(connection==null)return;
            CustomForm form=CustomForm.builder()
                    .title("Register")
                    .label("Create a password to join Ashfall.")
                    .input("Password","8–30 characters")
                    .input("Confirm Password","Enter it again")
                    .validResultHandler(response->{
                        String password=response.getInput(1),confirmation=response.getInput(2);
                        plugin.getServer().getScheduler().runTask(plugin,()->submit(player,password,confirmation));
                    })
                    .closedResultHandler(ignored->{
                        if(player.isOnline())plugin.getServer().getScheduler().runTaskLater(plugin,()->openBedrock(player),20L);
                    })
                    .build();
            connection.sendForm(form);
            formAttempts.remove(player.getUniqueId());
        }catch(Throwable error){
            plugin.getLogger().warning("Could not open Bedrock registration for "+player.getName()+": "+error.getClass().getSimpleName());
            int attempt=formAttempts.merge(player.getUniqueId(),1,Integer::sum);
            if(!player.isOnline())return;
            if(attempt<3){
                CoreUtil.error(player,"Registration is starting; please wait a moment.");
                plugin.getServer().getScheduler().runTaskLater(plugin,()->openBedrock(player),40L);
            }else{
                formAttempts.remove(player.getUniqueId());
                CoreUtil.error(player,"Registration could not start. Please rejoin to try again.");
            }
        }
    }
    void quit(Player player){formAttempts.remove(player.getUniqueId());}

    private void submit(Player player,String password,String confirmation){
        if(!player.isOnline())return;
        if(password==null||confirmation==null||!password.equals(confirmation)){
            CoreUtil.error(player,"Passwords do not match.");
            plugin.getServer().getScheduler().runTaskLater(plugin,()->openBedrock(player),10L);
            return;
        }
        if(password.length()<8||password.length()>30||password.chars().anyMatch(Character::isWhitespace)){
            CoreUtil.error(player,"Use 8–30 characters with no spaces.");
            plugin.getServer().getScheduler().runTaskLater(plugin,()->openBedrock(player),10L);
            return;
        }
        player.performCommand("register "+password+" "+confirmation);
        plugin.getServer().getScheduler().runTaskLater(plugin,()->{
            if(player.isOnline()&&!AuthMeApi.getInstance().isRegistered(player.getName()))openBedrock(player);
        },20L);
    }
}
