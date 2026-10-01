package com.chris.sharkhub.car.kanzi

/**
 * Kanzi's property data types and the built-in property names a kzb never lists in its own
 * `$property_types` table (verified on BYD's PA_RTL and Rage Mode files by kzb_place.py). Values
 * are sized by type: 0 float 1 int 2 bool 3 colour 4/5/6 vec2/3/4 7 mat3 8 mat4 9 string 10 pointer
 * 11 resource 12 SRT2D 13 SRT3D; strings and resources are `u32 len + chars`.
 */
object KanziTypes {
    val SIZE: Map<Int, Int?> = mapOf(0 to 4, 1 to 4, 2 to 4, 3 to 16, 4 to 8, 5 to 12, 6 to 16, 7 to 36, 8 to 64, 9 to null, 10 to 4, 11 to null, 12 to 28, 13 to 40)

    val BUILTIN: Map<String, Int> = mapOf(
        "Node3D.RenderTransformation" to 13, "Node3D.LayoutTransformation" to 13,
        "Node.Visible" to 2, "Node.StateManager" to 11, "Node.Font" to 11, "Node.Locale" to 9, "Node.Name" to 9, "Node.Path" to 9,
        "Node.Width" to 0, "Node.Height" to 0, "Node.Depth" to 0, "Node.Opacity" to 0, "Node.Enabled" to 2, "Node.HitTestable" to 2,
        "Node.Style" to 11, "Node.ContentStretch" to 1, "Node.HorizontalAlignment" to 1, "Node.VerticalAlignment" to 1,
        "Node.DepthAlignment" to 1, "Node.Projection2DTo3DScale" to 0, "Node.ClipChildren" to 2, "Node.Focusable" to 2,
        "Node.VisibleAmountInParent" to 0, "Node.HorizontalMargin" to 4, "Node.VerticalMargin" to 4, "Node.DepthMargin" to 4,
        "Node2D.ForegroundHint" to 1, "Node2D.BackgroundBrush" to 11, "Node2D.ForegroundBrush" to 11,
        "Node2D.OffscreenRendering" to 2, "Node2D.RenderTarget" to 11, "Node2D.LayoutTransformation" to 12,
        "Node2D.RenderTransformation" to 12, "Node2D.RenderTransformationOrigin" to 4, "Node2D.CompositionBrush" to 11,
        "Node3D.FrustumCullMargin" to 0, "Node3D.FinalTransformation" to 8,
        "Model3D.Mesh" to 11, "Model3D.Material" to 11, "Model3D.DrawnAsBoundingBox" to 2, "PrefabViewConcept.Prefab" to 11,
        "Scene.Camera" to 9, "Scene.HitTestCamera" to 9, "Scene.RenderPass" to 11, "Scene.ComposerReference" to 11,
        "Camera.Fov" to 0, "Camera.FovType" to 1, "Camera.ZFar" to 0, "Camera.ZNear" to 0, "Camera.AspectRatio" to 0,
        "Camera.DisableAspectRatio" to 2, "Camera.OrthogonalCoordinateSystemType" to 1, "Camera.OrthogonalPlaneHeight" to 0,
        "Camera.ProjectionType" to 1,
        "AnimationPlayer.AutoplayEnabled" to 2, "AnimationPlayer.DurationScale" to 0, "AnimationPlayer.PlaybackMode" to 1,
        "AnimationPlayer.RelativePlayback" to 2, "AnimationPlayer.RepeatCount" to 1,
        "AnimationPlayer.RestoreOriginalValuesAfterPlayback" to 2, "AnimationPlayer.Timeline" to 11,
        "PropertyDrivenAnimationPlayer.Enabled" to 2, "PropertyDrivenAnimationPlayer.RelativePlayback" to 2,
        "PropertyDrivenAnimationPlayer.Timeline" to 11, "PropertyDrivenAnimationPlayer.TimePropertyTypeProperty" to 9,
        "Action.Delay" to 1, "MessageArgument.SetPropertyAction.TargetObjectPath" to 9,
        "TextBlockConcept.FontSize" to 0, "TextBlockConcept.Text" to 9, "TextBlockConcept.TextHorizontalAlignment" to 1,
        "TextBlockConcept.TextVerticalAlignment" to 1, "TextBlockConcept.FontColor" to 3, "TextBlockConcept.LineSpacing" to 0,
        "TextBlockConcept.CharacterSpacing" to 0, "TextBlockConcept.FontMaterial" to 11, "TextBlockConcept.GlyphTexture" to 11,
        "TextBlockConcept.WordWrap" to 2, "TextBlockConcept.ConstrainContentHeight" to 2, "TextBlockConcept.FixedCharacterWidth" to 0,
        "TextBlockConcept.Overflow" to 9, "TextBlockConcept.RemoveSideBearings" to 2, "TextBlockConcept.TextHorizontalPadding" to 4,
        "TextBlockConcept.TextVerticalPadding" to 4, "TextBlockConcept.TwoPassRendering" to 2,
        "Page.SlideOffset" to 0, "Page.TransitionPhase" to 0, "Page.State" to 1, "Page.AutoActivate" to 2,
        "ScrollViewConcept.ScrollPosition" to 4,
        "PrefabView3D.InstantiateByFirstAttach" to 2, "PrefabView3D.InstanceNameProperty" to 9,
        "PrefabView3D.ItemPrefabTemplatePath" to 9, "PrefabView3D.AttachedProperty" to 2,
        "FaceToCamera" to 2, "FaceToCameraTargetCamera" to 9,
        "LightColorScale" to 0, "LightEnabled" to 2, "LightPropertyType" to 9,
        "BaseDataSource.DebugPrint" to 2, "BaseDataSource.XMLDataSourceFile" to 9,
        "TextureBrush.RenderTexture" to 11, "ColorBrush.Color" to 3, "Brush.ModulateColor" to 3,
        "Texture" to 11, "Ambient" to 3, "Diffuse" to 3, "Emissive" to 3, "SpecularColor" to 3, "SpecularExponent" to 0, "BlendMode" to 1,
        "TextureTiling" to 4, "TextureOffset" to 4, "GlobalAmbient" to 3, "BlendIntensity" to 0, "Exposure" to 0,
        "BlitRenderPass.Texture0" to 11, "BlitRenderPass.Material" to 11,
        "MaterialChrome.Falloff" to 0, "MaterialChrome.TextureCube" to 11, "MaterialChrome.FresnelPower" to 0,
        "ClearRenderPass.ClearColor" to 3, "ClearRenderPass.ClearDepth" to 0, "PipelineStateRenderPass.DepthTestFunction" to 1,
        "PipelineStateRenderPass.DepthWriteEnabled" to 2, "DrawObjectsRenderPass.Camera" to 9,
        "DrawObjectsRenderPass.ObjectSource" to 11, "PipelineStateRenderPass.Viewport" to 6,
        "PipelineStateRenderPass.ViewportMode" to 1, "CompositionTargetRenderPass.CompositionTarget" to 11,
        "PipelineStateRenderPass.ColorWriteMode" to 1, "PipelineStateRenderPass.CullMode" to 1,
        "CompositionTargetRenderPass.ResolveImmediately" to 2, "CompositionTargetRenderPass.MultisampleLevel" to 1,
        "CompositionTargetRenderPass.PixelFormat" to 1,
        "PointLightColor" to 3, "PointLightPosition" to 5, "PointLightAttenuation" to 5, "PointLightRadius" to 0,
        "DirectionalLightColor" to 3, "DirectionalLightDirection" to 5, "SpotLightColor" to 3, "SpotLightDirection" to 5,
        "SpotLightPosition" to 5, "SpotLightAttenuation" to 5, "SpotLightCutoffAngle" to 0, "SpotLightExponent" to 0,
        "ChromeDefault" to 11, "FresnelPower" to 0, "ReflectionPower" to 0, "Roughness" to 0, "Metallic" to 0,
        "StateManager.State" to 9, "StateManager.StateGroup" to 9,
        "Node.HitTestableContainer" to 2, "Node.ActualWidth" to 0, "Node.ActualHeight" to 0, "Node2D.SnapToPixel" to 2,
        "Screen.ClearColor" to 3, "Window.Height" to 1, "Window.Width" to 1, "Window.MetricsType" to 1, "Window.Orientation" to 1,
        "BlitRenderPass.Texture1" to 11, "BlitRenderPass.Texture2" to 11, "CompositionTargetRenderPass.ResolutionMultiplier" to 0,
        "ClearRenderPass.ClearStencil" to 1, "PipelineStateRenderPass.BlendMode" to 1,
        "RangeConcept.MaximumValue" to 0, "RangeConcept.MinimumValue" to 0, "RangeConcept.Value" to 0, "RangeConcept.NormalizedValue" to 0,
        "RangeConcept.Step" to 0, "GridLayoutConcept.ColumnDefinitions" to 9, "GridLayoutConcept.RowDefinitions" to 9,
        "GridLayoutConcept.Column" to 1, "GridLayoutConcept.Row" to 1, "GridLayoutConcept.ColumnSpan" to 1, "GridLayoutConcept.RowSpan" to 1,
        "GridLayoutConcept.Direction" to 1, "Image2D.Image" to 11, "TrajectoryLayoutConcept.Trajectory" to 11,
        "TrajectoryLayoutConcept.OverrideOffset" to 0, "TrajectoryLayoutConcept.ItemAreaBegin" to 0, "TrajectoryLayoutConcept.ItemAreaEnd" to 0,
        "OnPropertyChangedTrigger.SourceNode" to 9, "OnPropertyChangedTrigger.SourcePropertyType" to 9,
        "DispatchMessageAction.DispatchMode" to 1, "DispatchMessageAction.RoutingTarget" to 9, "DispatchMessageAction.RoutingTargetLookup" to 9,
        "MessageType" to 9, "NodeComponent.NodeComponentMessageArguments.TargetName" to 9,
        "AnimationPlayer.PlayMessageArguments.PlaybackMode" to 1, "AnimationPlayer.PlayMessageArguments.DurationScale" to 0,
        "AnimationPlayer.PlayMessageArguments.RepeatCount" to 1, "PropertyTargetInterpolator.Acceleration" to 0,
        "PropertyTargetInterpolator.Drag" to 0, "PropertyTargetInterpolator.InterpolatedPropertyField" to 1,
        "PropertyTargetInterpolator.InterpolatedPropertyType" to 9, "MessageTrigger.MessageSource" to 9,
        "MessageTrigger.RoutingMode" to 1, "MessageTrigger.SetHandled" to 2, "MessageArgument.WriteLog.Message" to 9,
        "NodeComponent.Name" to 9, "ExpressionCondition.Expression" to 9, "DataContext.DataContext" to 11, "DataContext.ItemsSource" to 9,
    )

    val NODE_CLASSES: Set<String> = setOf(
        "PrefabPlaceholder", "Kanzi.EmptyNode3D", "Kanzi.PrefabView3D", "Kanzi.Viewport2D", "Kanzi.EmptyNode2D", "Kanzi.Scene",
        "Kanzi.Camera", "Kanzi.Light", "DynamicPrefabView3D", "Kanzi.Model3D", "Kanzi.TextBlock3D",
        "Kanzi.TextBlock2D", "Kanzi.Node3D", "Kanzi.Node2D", "Kanzi.Page", "Kanzi.PageHost", "Kanzi.Image2D",
        "Kanzi.StackLayout3D", "Kanzi.StackLayout2D", "Kanzi.Instantiator3D", "Kanzi.Viewport3D",
        "Kanzi.Screen", "Kanzi.Button2D", "Kanzi.Button3D", "Kanzi.ToggleButton2D", "Kanzi.ToggleButton3D",
        "Kanzi.GridLayout2D", "Kanzi.GridLayout3D", "Kanzi.DockLayout2D", "Kanzi.DockLayout3D",
        "Kanzi.FlowLayout2D", "Kanzi.FlowLayout3D", "Kanzi.TrajectoryLayout2D", "Kanzi.TrajectoryLayout3D",
        "Kanzi.ScrollView2D", "Kanzi.ScrollView3D", "Kanzi.Slider2D", "Kanzi.Slider3D", "Kanzi.ListBox2D",
        "Kanzi.ListBox3D", "Kanzi.GridListBox2D", "Kanzi.GridListBox3D", "Kanzi.TrajectoryListBox3D",
        "Kanzi.PrefabView2D", "Kanzi.Portal", "Kanzi.LevelOfDetail3D", "Kanzi.NinePatchImage2D",
        "Kanzi.ProgressiveRenderingViewport2D",
    )

    val PREFAB_PROPS = listOf("PrefabViewConcept.Prefab", "PrefabView3D.ItemPrefabTemplatePath")
}
