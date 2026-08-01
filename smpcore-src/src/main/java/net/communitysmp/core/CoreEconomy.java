package net.communitysmp.core;

import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;

final class CoreEconomy extends AbstractEconomy {
    private final SMPCore plugin;
    CoreEconomy(SMPCore plugin){this.plugin=plugin;}
    private String key(String name){return CoreUtil.id(name);}
    private void ensure(String name){plugin.db().ensurePlayer(key(name),name,plugin.getConfig().getDouble("starting-balance",250));}
    @Override public boolean isEnabled(){return plugin.isEnabled();}
    @Override public String getName(){return "SMPCore Economy";}
    @Override public boolean hasBankSupport(){return false;}
    @Override public int fractionalDigits(){return 2;}
    @Override public String format(double amount){return CoreUtil.money(amount);}
    @Override public String currencyNamePlural(){return "dollars";}
    @Override public String currencyNameSingular(){return "dollar";}
    @Override public boolean hasAccount(String playerName){return plugin.db().player(key(playerName))!=null;}
    @Override public boolean hasAccount(String playerName,String worldName){return hasAccount(playerName);}
    @Override public double getBalance(String playerName){ensure(playerName);return plugin.db().player(key(playerName)).balance();}
    @Override public double getBalance(String playerName,String world){return getBalance(playerName);}
    @Override public boolean has(String playerName,double amount){return getBalance(playerName)+0.00001>=amount;}
    @Override public boolean has(String playerName,String world,double amount){return has(playerName,amount);}
    @Override public EconomyResponse withdrawPlayer(String playerName,double amount){if(!CoreUtil.finitePositive(amount))return response(0,getBalance(playerName),EconomyResponse.ResponseType.FAILURE,"Invalid amount");ensure(playerName);boolean ok=plugin.db().changeBalance(key(playerName),-amount);return response(ok?amount:0,getBalance(playerName),ok?EconomyResponse.ResponseType.SUCCESS:EconomyResponse.ResponseType.FAILURE,ok?"":"Insufficient funds");}
    @Override public EconomyResponse withdrawPlayer(String playerName,String world,double amount){return withdrawPlayer(playerName,amount);}
    @Override public EconomyResponse depositPlayer(String playerName,double amount){if(!CoreUtil.finitePositive(amount))return response(0,getBalance(playerName),EconomyResponse.ResponseType.FAILURE,"Invalid amount");ensure(playerName);plugin.creditEarned(key(playerName),amount,"VAULT_DEPOSIT");return response(amount,getBalance(playerName),EconomyResponse.ResponseType.SUCCESS,"");}
    @Override public EconomyResponse depositPlayer(String playerName,String world,double amount){return depositPlayer(playerName,amount);}
    @Override public boolean createPlayerAccount(String playerName){ensure(playerName);return true;}
    @Override public boolean createPlayerAccount(String playerName,String world){return createPlayerAccount(playerName);}
    private EconomyResponse response(double amount,double balance,EconomyResponse.ResponseType type,String error){return new EconomyResponse(amount,balance,type,error);}
    private EconomyResponse noBanks(){return response(0,0,EconomyResponse.ResponseType.NOT_IMPLEMENTED,"Faction banking is managed with /f deposit and /f withdraw");}
    @Override public EconomyResponse createBank(String name,String player){return noBanks();}
    @Override public EconomyResponse deleteBank(String name){return noBanks();}
    @Override public EconomyResponse bankBalance(String name){return noBanks();}
    @Override public EconomyResponse bankHas(String name,double amount){return noBanks();}
    @Override public EconomyResponse bankWithdraw(String name,double amount){return noBanks();}
    @Override public EconomyResponse bankDeposit(String name,double amount){return noBanks();}
    @Override public EconomyResponse isBankOwner(String name,String player){return noBanks();}
    @Override public EconomyResponse isBankMember(String name,String player){return noBanks();}
    @Override public List<String> getBanks(){return Collections.emptyList();}
    @Override public boolean hasAccount(OfflinePlayer player){return hasAccount(player.getName());}
}
