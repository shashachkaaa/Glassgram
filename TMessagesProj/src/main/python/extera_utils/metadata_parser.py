"""Reads plugin metadata from top-level constants without running the plugin."""

import ast

KEYS = (
    "__id__", "__name__", "__description__", "__author__", "__version__", "__icon__",
    "__app_version__", "__sdk_version__", "__requirements__", "__min_version__",
)


def get_metadata_from_source(source: str) -> dict:
    tree = ast.parse(source)
    result = {}
    for node in tree.body:
        targets = []
        if isinstance(node, ast.Assign):
            targets = node.targets
            value = node.value
        elif isinstance(node, ast.AnnAssign) and node.value is not None:
            targets = [node.target]
            value = node.value
        else:
            continue
        for target in targets:
            if isinstance(target, ast.Name) and target.id in KEYS:
                try:
                    result[target.id] = ast.literal_eval(value)
                except Exception:
                    pass
    return result


def get_metadata(file_path):
    with open(file_path, "r", encoding="utf-8-sig") as f:
        return get_metadata_from_source(f.read())
