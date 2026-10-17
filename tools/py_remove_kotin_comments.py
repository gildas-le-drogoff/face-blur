#!/usr/bin/env python3
"""
Supprime les commentaires de tous les fichiers dans un répertoire donné.
Supporte les commentaires C-style (/* ... */, //) et Python (#).
"""

import os
import re
import sys
import argparse
from pathlib import Path


def remove_comments(content):
    """
    Supprime les commentaires du contenu d'un fichier.
    Supporte les commentaires C-style (/* */, //) et Python (#).
    """
    # Supprimer les commentaires multi-lignes /* ... */
    # Utilisation d'une expression régulière non-gourmande avec DOTALL
    content = re.sub(r"/\*.*?\*/", "", content, flags=re.DOTALL)

    # Supprimer les commentaires sur une ligne // (mais pas dans les chaînes)
    # On va traiter ligne par ligne pour éviter de supprimer // dans les chaînes
    lines = content.split("\n")
    new_lines = []

    for line in lines:
        # Vérifier si // est dans une chaîne
        in_string = False
        string_char = None
        i = 0
        new_line = []

        while i < len(line):
            char = line[i]

            # Gérer les chaînes de caractères
            if char in ('"', "'") and (i == 0 or line[i - 1] != "\\"):
                if not in_string:
                    in_string = True
                    string_char = char
                elif string_char == char:
                    in_string = False
                new_line.append(char)
                i += 1
                continue

            # Si on est dans une chaîne, on ajoute tout
            if in_string:
                new_line.append(char)
                i += 1
                continue

            # Vérifier si on a un commentaire //
            if char == "/" and i + 1 < len(line) and line[i + 1] == "/":
                # On ignore le reste de la ligne
                break

            new_line.append(char)
            i += 1

        new_lines.append("".join(new_line))

    content = "\n".join(new_lines)

    # Supprimer les commentaires Python (#) sur une ligne
    # (garder les # dans les chaînes)
    lines = content.split("\n")
    new_lines = []

    for line in lines:
        in_string = False
        string_char = None
        i = 0
        new_line = []

        while i < len(line):
            char = line[i]

            # Gérer les chaînes
            if char in ('"', "'") and (i == 0 or line[i - 1] != "\\"):
                if not in_string:
                    in_string = True
                    string_char = char
                elif string_char == char:
                    in_string = False
                new_line.append(char)
                i += 1
                continue

            if in_string:
                new_line.append(char)
                i += 1
                continue

            # Commentaire Python #
            if char == "#":
                break

            new_line.append(char)
            i += 1

        new_lines.append("".join(new_line))

    return "\n".join(new_lines)


def should_process_file(filepath):
    """
    Détermine si un fichier doit être traité.
    """
    # Extensions de fichiers qui peuvent contenir des commentaires
    extensions = {
        ".py",
        ".c",
        ".cpp",
        ".h",
        ".hpp",
        ".java",
        ".js",
        ".ts",
        ".css",
        ".scss",
        ".html",
        ".xml",
        ".json",
        ".yaml",
        ".yml",
        ".sh",
        ".bash",
        ".zsh",
        ".fish",
        ".go",
        ".rs",
        ".rb",
        ".php",
        ".swift",
        ".kt",
        ".kts",
        ".dart",
        ".lua",
        ".r",
        ".sql",
        ".pl",
        ".pm",
        ".t",
        ".pod",
    }

    # Noms de fichiers à traiter
    filenames = {"Makefile", "Dockerfile", "CMakeLists.txt"}

    path = Path(filepath)
    return path.suffix.lower() in extensions or path.name in filenames


def process_file(filepath, dry_run=False, verbose=False):
    """
    Traite un fichier individuel.
    """
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            content = f.read()
    except UnicodeDecodeError:
        # Essayer avec une autre encodage
        try:
            with open(filepath, "r", encoding="latin-1") as f:
                content = f.read()
        except Exception as e:
            if verbose:
                print(f"⚠️  Impossible de lire {filepath}: {e}")
            return False
    except Exception as e:
        if verbose:
            print(f"⚠️  Impossible de lire {filepath}: {e}")
        return False

    # Vérifier si le fichier contient des commentaires
    has_comments = bool(re.search(r"/\*|\*/|//|#", content))
    if not has_comments:
        if verbose:
            print(f"ℹ️  Pas de commentaires dans {filepath}")
        return True

    # Supprimer les commentaires
    new_content = remove_comments(content)

    # Vérifier si le contenu a changé
    if new_content == content:
        if verbose:
            print(f"ℹ️  Aucun changement dans {filepath}")
        return True

    if dry_run:
        print(f"📝 {filepath} - Des commentaires seraient supprimés")
        return True

    # Écrire le nouveau contenu
    try:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(new_content)
        if verbose:
            print(f"✅ {filepath} - Commentaires supprimés")
        return True
    except Exception as e:
        print(f"❌ Erreur lors de l'écriture de {filepath}: {e}")
        return False


def walk_directory(directory, dry_run=False, verbose=False):
    """
    Parcourt récursivement un répertoire et traite les fichiers.
    """
    directory = Path(directory)
    if not directory.exists():
        print(f"❌ Le répertoire {directory} n'existe pas")
        return False

    if not directory.is_dir():
        print(f"❌ {directory} n'est pas un répertoire")
        return False

    processed = 0
    modified = 0
    errors = 0

    # Parcourir le répertoire
    for root, dirs, files in os.walk(directory):
        # Ignorer les répertoires cachés et certains dossiers
        dirs[:] = [
            d
            for d in dirs
            if not d.startswith(".")
            and d
            not in {
                "node_modules",
                "__pycache__",
                "venv",
                "env",
                ".git",
                "dist",
                "build",
            }
        ]

        for file in files:
            filepath = Path(root) / file

            # Ignorer les fichiers cachés
            if file.startswith("."):
                continue

            if should_process_file(filepath):
                processed += 1
                if process_file(filepath, dry_run, verbose):
                    # Vérifier si le fichier a été modifié
                    if not dry_run:
                        # On vérifie si le contenu a changé en relisant le fichier
                        # (pour les besoins du comptage)
                        try:
                            with open(filepath, "r", encoding="utf-8") as f:
                                content = f.read()
                            # Si le fichier n'a plus de commentaires, on considère qu'il a été modifié
                            if not re.search(r"/\*|\*/|//|#", content):
                                modified += 1
                        except:
                            pass
                else:
                    errors += 1

    print(f"\n📊 Résumé:")
    print(f"   Fichiers traités: {processed}")
    if dry_run:
        print(f"   Mode simulation (aucun fichier modifié)")
    else:
        print(f"   Fichiers modifiés: {modified}")
    print(f"   Erreurs: {errors}")

    return errors == 0


def main():
    parser = argparse.ArgumentParser(
        description="Supprime les commentaires de tous les fichiers dans un répertoire",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Exemples:
  %(prog)s /path/to/project
  %(prog)s /path/to/project --dry-run
  %(prog)s /path/to/project --verbose
  %(prog)s /path/to/project --dry-run --verbose
        """,
    )

    parser.add_argument("path", help="Chemin du répertoire à traiter")

    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Affiche ce qui serait fait sans modifier les fichiers",
    )

    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Affiche plus d'informations"
    )

    args = parser.parse_args()

    # Vérifier que le chemin existe
    if not os.path.exists(args.path):
        print(f"❌ Le chemin '{args.path}' n'existe pas")
        sys.exit(1)

    # Traiter le répertoire
    success = walk_directory(args.path, args.dry_run, args.verbose)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
