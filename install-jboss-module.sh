#!/bin/bash
#
# Installe le module JBoss io.sqlcommenter.agent et ajoute la dépendance
# dans le module org.jboss.ironjacamar.jdbcadapters.
#
# Usage: ./install-jboss-module.sh /opt/jboss-eap-8.0.7
#
set -e

JBOSS="${1:-/opt/jboss-eap-8.0.7}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -d "$JBOSS" ]; then
    echo "ERREUR: répertoire JBoss introuvable : $JBOSS"
    echo "Usage: $0 <JBOSS_HOME>"
    exit 1
fi

echo "=== Installation du module SQL Commenter sur $JBOSS ==="

# --- Étape 1 : installer le module io.sqlcommenter.agent ---
MODULE_DIR="$JBOSS/modules/io/sqlcommenter/agent/main"
echo "[1/4] Création du module : $MODULE_DIR"
mkdir -p "$MODULE_DIR"

# Localiser les fichiers source (module.xml + runtime jar)
RUNTIME_JAR=$(find "$SCRIPT_DIR" -name "sqlcommenter-runtime.jar" | head -1)
MODULE_XML=$(find "$SCRIPT_DIR" -path "*sqlcommenter/agent/main/module.xml" | head -1)

if [ -z "$RUNTIME_JAR" ] || [ -z "$MODULE_XML" ]; then
    echo "ERREUR: sqlcommenter-runtime.jar ou module.xml introuvable près de $SCRIPT_DIR"
    exit 1
fi

cp "$RUNTIME_JAR" "$MODULE_DIR/sqlcommenter-runtime.jar"
cp "$MODULE_XML"  "$MODULE_DIR/module.xml"
echo "      ✓ Module installé"

# --- Étape 2 : localiser le module.xml d'IronJacamar ---
echo "[2/4] Recherche du module IronJacamar..."
IJ_MODULE=$(find "$JBOSS/modules" -path "*ironjacamar/jdbcadapters/main/module.xml" | head -1)

if [ -z "$IJ_MODULE" ]; then
    echo "ERREUR: module.xml d'IronJacamar introuvable"
    echo "Cherché dans : $JBOSS/modules/**/ironjacamar/jdbcadapters/main/"
    exit 1
fi
echo "      ✓ Trouvé : $IJ_MODULE"

# --- Étape 3 : ajouter la dépendance ---
echo "[3/4] Ajout de la dépendance io.sqlcommenter.agent..."

if grep -q 'name="io.sqlcommenter.agent"' "$IJ_MODULE"; then
    echo "      ✓ Dépendance déjà présente, rien à faire"
else
    # Sauvegarde
    cp "$IJ_MODULE" "$IJ_MODULE.bak.$(date +%Y%m%d-%H%M%S)"
    echo "      ✓ Sauvegarde créée : $IJ_MODULE.bak.*"

    # Insérer <module name="io.sqlcommenter.agent"/> juste après <dependencies>
    # Utilise sed pour insérer après la ligne contenant <dependencies>
    if grep -q '<dependencies>' "$IJ_MODULE"; then
        sed -i 's|<dependencies>|<dependencies>\n        <module name="io.sqlcommenter.agent"/>|' "$IJ_MODULE"
        echo "      ✓ Dépendance ajoutée dans <dependencies>"
    else
        echo "ERREUR: pas de section <dependencies> dans $IJ_MODULE"
        echo "Ajoutez manuellement avant </module> :"
        echo '  <dependencies><module name="io.sqlcommenter.agent"/></dependencies>'
        exit 1
    fi
fi

# --- Étape 4 : instructions de redémarrage ---
echo "[4/4] Installation terminée."
echo ""
echo "=== ÉTAPES SUIVANTES ==="
echo "1. Redémarrer JBoss (modification d'un module système) :"
echo "   $JBOSS/bin/jboss-cli.sh --connect :shutdown"
echo "   puis redémarrer le serveur"
echo ""
echo "2. Réattacher l'agent :"
echo "   java -jar sql-commenter-agent-1.1.0.jar --pid <PID> --args config=/tmp/sqlcommenter.properties"
echo ""
echo "3. Vérifier dans server.log :"
echo "   grep 'First SQL instrumented' server.log"
echo ""
echo "Les commentaires SQL devraient maintenant apparaître dans Oracle."
