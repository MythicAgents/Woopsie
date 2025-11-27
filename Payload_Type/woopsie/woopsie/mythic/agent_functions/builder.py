import asyncio
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import traceback
from pathlib import Path
from mythic_container.PayloadBuilder import *
from mythic_container.MythicRPC import *


class Woopsie(PayloadType):
    name = "woopsie"
    file_extension = "jar"
    author = "@haha150"
    supported_os = [
        SupportedOS.Windows,
        SupportedOS.Linux,
        SupportedOS.MacOS,
    ]
    mythic_encrypts = True
    wrapper = False
    wrapped_payloads = []
    note = "Woopsie is a cross-platform C2 agent written in Java."
    supports_dynamic_loading = False
    supports_multiple_c2_instances_in_build = False
    supports_multiple_c2_in_build = False
    
    build_parameters = [
        BuildParameter(
            name="output",
            parameter_type=BuildParameterType.ChooseOne,
            description="Payload output format (native Windows builds are experimental)",
            default_value="jar",
            choices=["jar", "native"],
            required=True,
        ),
        BuildParameter(
            name="debug",
            parameter_type=BuildParameterType.Boolean,
            description="Enable debug logging (logs all HTTP requests/responses and operations)",
            default_value=False,
            required=False,
        ),
        BuildParameter(
            name="adjust_filename",
            parameter_type=BuildParameterType.Boolean,
            description="Automatically adjust payload extension based on selected choices.",
            default_value=True,
            required=False,
        ),
    ]
    
    c2_profiles = ["http", "httpx"]

    c2_parameter_deviations = {
        "http": {
            "get_uri": C2ParameterDeviation(supported=False),
            "query_path_name": C2ParameterDeviation(supported=False),
        }
    }

    agent_path = pathlib.Path(".") / "woopsie" / "mythic"
    agent_code_path = pathlib.Path(".") / "woopsie" / "agent_code"
    agent_icon_path = agent_path / "agent_icon" / "woopsie.svg"
    
    async def build(self) -> BuildResponse:
        resp = BuildResponse(status=BuildStatus.Error)
        
        try:
            # Get build parameters
            output_format = self.get_parameter("output")
            selected_os = self.selected_os  # Get selected OS from Mythic
            
            # Set file extension based on output format and OS
            if output_format == "native":
                if selected_os == "Windows":
                    self.file_extension = "exe"
                else:
                    self.file_extension = "bin"
            else:
                self.file_extension = "jar"
            
            # Prepare environment variables for build (like oopsie does)
            c2 = self.c2info[0]
            profile = c2.get_c2profile()["name"]
            
            # Get all C2 parameters and add UUID like oopsie does
            c2_params = c2.get_parameters_dict()
            c2_params["UUID"] = self.uuid
            c2_params["profile"] = profile
            
            # Add build parameters
            c2_params["debug"] = str(self.get_parameter("debug"))
            
            # Build environment from c2_params - convert all to env format
            build_env = {}
            for key, val in c2_params.items():
                if isinstance(val, str):
                    # Handle raw_c2_config specially - fetch file content like oopsie
                    if key == "raw_c2_config":
                        response = await SendMythicRPCFileGetContent(MythicRPCFileGetContentMessage(val))
                        if response.Success:
                            val = response.Content.decode('utf-8')
                            try:
                                config = json.loads(val)
                            except json.JSONDecodeError:
                                resp.build_message = f"Failed to parse raw_c2_config JSON: {val}"
                                resp.status = BuildStatus.Error
                                return resp
                            val = json.dumps(config)
                        else:
                            resp.build_message = "Failed to get raw C2 config file"
                            resp.status = BuildStatus.Error
                            return resp
                    build_env[key.upper()] = val
                elif isinstance(val, (int, bool)):
                    build_env[key.upper()] = str(val)
                elif isinstance(val, dict):
                    # Handle headers specially to extract User-Agent
                    if key == "headers":
                        build_env["USER_AGENT"] = val.get("User-Agent", "Mozilla/5.0")
                    # Store full dict as JSON too in case needed
                    build_env[key.upper()] = json.dumps(val)
                elif isinstance(val, list):
                    # Handle lists (like callback_domains for httpx)
                    build_env[key.upper()] = json.dumps(val)
                else:
                    build_env[key.upper()] = str(val)
            
            # Ensure USER_AGENT is set if not already
            if "USER_AGENT" not in build_env:
                build_env["USER_AGENT"] = "Mozilla/5.0"
            
            # Add build environment to message for visibility
            resp.build_message += "\nBuild Configuration:\n"
            for key, value in build_env.items():
                # Mask UUID for security
                display_value = value
                resp.build_message += f"  {key}: {display_value}\n"
            resp.build_message += "\n"
            
            # Build with Maven
            if output_format == "native":
                # Native builds only supported for Linux in this container
                # For Windows native .exe, use Dockerfile.windows on Windows host
                if selected_os == "Windows":
                    resp.build_message = "Windows native builds require Windows container (Dockerfile.windows).\n"
                    resp.build_message += "Falling back to JAR format for Windows...\n"
                    output_format = "jar"
                else:
                    # Linux: Use GraalVM Native Image
                    resp.build_message += f"Building native {selected_os} executable with GraalVM...\n"
                    await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                        PayloadUUID=self.uuid,
                        StepName="Compiling Native Image",
                        StepStdout=f"Building native executable for {selected_os}...",
                        StepSuccess=True
                    ))
                    
                    build_result = await self.run_native_build(selected_os, build_env)
                    
                    if not build_result["success"]:
                        resp.build_message += f"\nNative build failed: {build_result['error']}"
                        resp.status = BuildStatus.Error
                        return resp
                    
                    resp.build_message += f"Build command: {build_result['command']}\n"
                    
                    output_path = build_result["path"]
            else:
                resp.build_message += "Building JAR with Maven...\n"
                await SendMythicRPCPayloadUpdatebuildStep(MythicRPCPayloadUpdateBuildStepMessage(
                    PayloadUUID=self.uuid,
                    StepName="Compiling JAR",
                    StepStdout="Running Maven build...",
                    StepSuccess=True
                ))
                
                build_result = await self.run_maven_build(build_env)
                
                if not build_result["success"]:
                    resp.build_message += f"\nMaven build failed: {build_result['error']}"
                    resp.status = BuildStatus.Error
                    return resp
                
                resp.build_message += f"Build command: {build_result['command']}\n"
                
                jar_path = self.agent_code_path / "target" / "woopsie.jar"
                
                if not jar_path.exists():
                    resp.build_message = "JAR file not found after build"
                    resp.status = BuildStatus.Error
                    return resp
                
                output_path = jar_path
            
            # Read the final payload
            with open(output_path, "rb") as f:
                resp.payload = f.read()
            
            resp.status = BuildStatus.Success
            resp.build_message += f"Successfully built {output_format} payload"
            
            # Adjust filename based on output format and OS
            if self.get_parameter("adjust_filename"):
                resp.updated_filename = self.adjust_file_name(self.filename, output_format, selected_os)
            
        except Exception as e:
            resp.build_message = f"Error building payload: {str(e)}\n{traceback.format_exc()}"
            resp.status = BuildStatus.Error
        
        return resp
    
    def adjust_file_name(self, filename, output_type, selected_os):
        """Adjust filename based on output type and OS"""
        filename_pieces = filename.split(".")
        original_filename = ".".join(filename_pieces[:-1])
        
        if output_type == "native":
            if selected_os == "Linux":
                return original_filename + ".bin"
            elif selected_os == "Windows":
                return original_filename + ".exe"
            else:
                return original_filename + ".bin"
        else:  # jar
            return original_filename + ".jar"
    
    async def run_maven_build(self, build_env: dict) -> dict:
        """Run Maven JAR build"""
        try:
            # Merge build environment with system environment
            env = os.environ.copy()
            env.update(build_env)
            
            command = "mvn clean package -q"
            
            process = await asyncio.create_subprocess_exec(
                "mvn", "clean", "package", "-q",
                cwd=str(self.agent_code_path),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env
            )
            
            stdout, stderr = await process.communicate()
            
            if process.returncode != 0:
                return {
                    "success": False,
                    "error": f"Maven failed with code {process.returncode}\n{stderr.decode()}"
                }
            
            return {"success": True, "command": command}
            
        except Exception as e:
            return {"success": False, "error": str(e)}
    
    async def run_native_build(self, selected_os: str, build_env: dict) -> dict:
        """Run GraalVM Native Image build"""
        try:
            # Set up environment for native build
            env = os.environ.copy()
            env.update(build_env)
            env["GRAALVM_HOME"] = "/opt/graalvm"
            env["JAVA_HOME"] = "/opt/graalvm"
            
            # Configure for Windows cross-compilation if needed
            # Note: Windows cross-compilation is experimental and may have limitations
            # For production Windows builds, consider using a Windows build host
            if selected_os == "Windows":
                # Set up MinGW cross-compilation toolchain for Windows
                env["CC"] = "x86_64-w64-mingw32-gcc"
                env["CXX"] = "x86_64-w64-mingw32-g++"
            
            command = f"mvn clean package -Pnative -Dos.detected={selected_os.lower()}"
            
            # Build the native image with Maven native profile
            process = await asyncio.create_subprocess_exec(
                "mvn",
                "clean",
                "package",
                "-Pnative",
                f"-Dos.detected={selected_os.lower()}",
                cwd=str(self.agent_code_path),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=env
            )
            
            stdout, stderr = await process.communicate()
            
            if process.returncode != 0:
                return {
                    "success": False,
                    "error": f"Native build failed with code {process.returncode}\n{stderr.decode()}"
                }
            
            # Determine native executable path based on OS
            if selected_os == "Windows":
                native_path = self.agent_code_path / "target" / "woopsie.exe"
            else:
                native_path = self.agent_code_path / "target" / "woopsie"
            
            if not native_path.exists():
                return {
                    "success": False,
                    "error": f"Native executable not found at {native_path}"
                }
            
            return {
                "success": True,
                "path": native_path,
                "command": command
            }
            
        except Exception as e:
            return {"success": False, "error": str(e)}


