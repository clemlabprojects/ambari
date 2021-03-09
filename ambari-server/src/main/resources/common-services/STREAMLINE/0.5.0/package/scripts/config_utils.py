import os
from xml.etree import ElementTree
from resource_management.core.source import InlineTemplate
from resource_management.core.logger import Logger


def merge_env(conf_dict, content_property_name="content", property_prefix="content-"):
  result = conf_dict[content_property_name]
  if not result.endswith(os.linesep):
    result += os.linesep
  for k, v in conf_dict.items():
    if k.startswith(property_prefix):
      result = result + v + os.linesep
  return result


def get_additional_env_content(conf_dict, property_prefix="content-"):
  result = ""
  for k, v in conf_dict.items():
    if k.startswith(property_prefix):
      result = result + v + os.linesep
  return result


def append_xml_content(original_content, conf_dict, property_prefix="content-"):
  original_evaluated_config = InlineTemplate(original_content).get_content()
  original_config_root = ElementTree.fromstring(original_evaluated_config)
  for k, v in conf_dict.items():
    if k.startswith(property_prefix):
      try:
        evaluated_additional_value = InlineTemplate(v).get_content()
        additional_config_root = ElementTree.fromstring(evaluated_additional_value)
        original_config_root.append(additional_config_root)
      except ElementTree.ParseError:
        Logger.error(
          "Failed to evaluate property \"{0}\" to a valid xml, its content will not be appended to target ".format(k) +
          "file.\nIgnore this error if it is expected for this property to be evaluated in empty or malformed xml, "
          "otherwise change property value and restart service")
  return ElementTree.tostring(original_config_root)
