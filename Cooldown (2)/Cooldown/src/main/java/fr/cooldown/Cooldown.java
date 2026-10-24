package fr.cooldown;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class Cooldown extends JavaPlugin implements Listener {

    // ===== Valeurs modifiables =====
    private static final int COOLDOWN_PERLE_TICKS = 3 * 20;
    private static final int COOLDOWN_MACE_TICKS = 5 * 20;

    private final Map<Enchantment, Integer> caps = new HashMap<>();
    private NamespacedKey gappleKey;
    private NamespacedKey enchantedKey;

    @Override
    public void onEnable() {
        caps.put(Enchantment.PROTECTION, 3);
        caps.put(Enchantment.FEATHER_FALLING, 3);
        caps.put(Enchantment.SHARPNESS, 4);
        caps.put(Enchantment.RIPTIDE, 2);
        caps.put(Enchantment.LUNGE, 2);
        caps.put(Enchantment.DENSITY, 3);
        caps.put(Enchantment.BREACH, 2);

        getServer().getPluginManager().registerEvents(this, this);

        // Craft de la pomme d'or : pomme au centre + 4 lingots d'or en croix (remplace le craft vanilla)
        gappleKey = new NamespacedKey(this, "golden_apple");
        Bukkit.removeRecipe(NamespacedKey.minecraft("golden_apple"));
        Bukkit.removeRecipe(gappleKey);
        ShapedRecipe gapple = new ShapedRecipe(gappleKey, new ItemStack(Material.GOLDEN_APPLE));
        gapple.shape(" G ", "GAG", " G ");
        gapple.setIngredient('G', Material.GOLD_INGOT);
        gapple.setIngredient('A', Material.APPLE);
        Bukkit.addRecipe(gapple);

        // Craft de la pomme d'or enchantee : pomme au centre + 4 blocs d'or en croix
        enchantedKey = new NamespacedKey(this, "enchanted_golden_apple");
        Bukkit.removeRecipe(enchantedKey);
        ShapedRecipe enchanted = new ShapedRecipe(enchantedKey, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE));
        enchanted.shape(" G ", "GAG", " G ");
        enchanted.setIngredient('G', Material.GOLD_BLOCK);
        enchanted.setIngredient('A', Material.APPLE);
        Bukkit.addRecipe(enchanted);

        // Verification periodique (toutes les 0,5 s) : armure netherite + enchantements
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) {
                limitNetheriteArmor(p);
                sanitizeInventory(p);
            }
        }, 20L, 10L);
    }

    // ===== Armure netherite : 1 piece max =====
    private boolean isNetherite(ItemStack item) {
        if (item == null) return false;
        Material m = item.getType();
        return m == Material.NETHERITE_HELMET
                || m == Material.NETHERITE_CHESTPLATE
                || m == Material.NETHERITE_LEGGINGS
                || m == Material.NETHERITE_BOOTS;
    }

    private void limitNetheriteArmor(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean kept = false;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack it = inv.getItem(slot);
            if (!isNetherite(it)) continue;
            if (!kept) {
                kept = true;
                continue;
            }
            inv.setItem(slot, null);
            Map<Integer, ItemStack> reste = inv.addItem(it);
            for (ItemStack r : reste.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), r);
            }
            p.sendMessage("§cTu ne peux porter qu'une seule pièce d'armure en netherite.");
        }
    }

    // ===== Enchantements / mace =====
    private void sanitizeInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && sanitize(it)) {
                inv.setItem(i, it);
            }
        }
    }

    /** Retourne true si l'item a ete modifie. */
    private boolean sanitize(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        boolean changed = false;

        // Mace incassable
        if (item.getType() == Material.MACE && !meta.isUnbreakable()) {
            meta.setUnbreakable(true);
            changed = true;
        }

        boolean sword = Tag.ITEMS_SWORDS.isTagged(item.getType());
        EnchantmentStorageMeta book = (meta instanceof EnchantmentStorageMeta b) ? b : null;
        Map<Enchantment, Integer> current = (book != null) ? book.getStoredEnchants() : meta.getEnchants();

        for (Map.Entry<Enchantment, Integer> e : new HashMap<>(current).entrySet()) {
            Enchantment ench = e.getKey();
            int level = e.getValue();

            boolean remove = sword && ench.equals(Enchantment.FIRE_ASPECT);
            Integer cap = caps.get(ench);
            boolean cut = cap != null && level > cap;
            if (!remove && !cut) continue;

            changed = true;
            if (book != null) {
                book.removeStoredEnchant(ench);
                if (!remove) book.addStoredEnchant(ench, cap, true);
            } else {
                meta.removeEnchant(ench);
                if (!remove) meta.addEnchant(ench, cap, true);
            }
        }

        if (changed) item.setItemMeta(meta);
        return changed;
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack result = e.getResult();
        if (result != null && sanitize(result)) {
            e.setResult(result);
        }
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory().getResult();
        if (result != null && sanitize(result)) {
            e.getInventory().setResult(result);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.getPlayer().discoverRecipe(gappleKey);
        e.getPlayer().discoverRecipe(enchantedKey);
    }

    // ===== Cooldowns =====
    @EventHandler
    public void onPearl(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl && e.getEntity().getShooter() instanceof Player p) {
            p.setCooldown(Material.ENDER_PEARL, COOLDOWN_PERLE_TICKS);
        }
    }

    @EventHandler
    public void onMace(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p
                && p.getInventory().getItemInMainHand().getType() == Material.MACE) {
            p.setCooldown(Material.MACE, COOLDOWN_MACE_TICKS);
        }
    }
}
